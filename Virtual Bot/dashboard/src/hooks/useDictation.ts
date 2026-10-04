import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { t } from '@/locales/dictation';

/*
 * Dictation records into the composer draft through /api/asr.
 * The recorder becomes inactive synchronously; final bytes arrive before onstop.
 * Manual stop must survive permission/setup delays and still return recognized text.
 * A replaced or unmounted composer cancels its phrase and releases its resources.
 */

/** Hard limit for one phrase. */
const REC_MAX_MS = 15_000;
/** Silence after speech ends the phrase. */
const SILENCE_MS = 1300;
/** Ignore transients shorter than a spoken phrase. */
const MIN_REC_MS = 600;
/** Voice activity threshold. */
const VOL_SPEAK = 0.012;
/** Five-second partials avoid hallucinated words from very short audio. */
const PARTIAL_MS = 5000;
/** Stop an untouched microphone after this initial silence. */
const NO_SPEECH_MS = 6000;

export function useDictation() {
  const [stream, setStream] = useState<MediaStream | null>(null);
  /** Partial text while the phrase is still recording. */
  const [partial, setPartial] = useState('');
  /** Recording has stopped and the final transcription is pending. */
  const [recognizing, setRecognizing] = useState(false);
  const [error, setError] = useState('');
  const finishRef = useRef<(() => void) | null>(null);
  // The second press can arrive while getUserMedia/recorder setup is still
  // pending. Keep that intent until the recorder has an onstop handler.
  const stopRequestedRef = useRef(false);
  const generationRef = useRef(0);
  const cleanupRef = useRef<(() => void) | null>(null);

  const cancel = useCallback(() => {
    generationRef.current += 1;
    cleanupRef.current?.();
    cleanupRef.current = null;
    finishRef.current = null;
    stopRequestedRef.current = false;
    setStream(null);
    setPartial('');
    setRecognizing(false);
  }, []);

  useEffect(() => () => {
    generationRef.current += 1;
    cleanupRef.current?.();
    cleanupRef.current = null;
    finishRef.current = null;
  }, []);

  const listen = useCallback(async (): Promise<string | null> => {
    // Replacing a phrase cancels it; its queued events cannot own the new mic.
    cancel();
    const generation = generationRef.current;
    const current = () => generationRef.current === generation;
    setError('');
    setPartial('');
    setRecognizing(false);

    let media: MediaStream;
    try {
      // Keep browser noise suppression and gain control enabled, as on the screen.
      media = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (cause) {
      if (current()) {
        stopRequestedRef.current = false;
        setError(t((cause as Error)?.name === 'NotAllowedError' ? 'denied' : 'unavailable'));
      }
      return null;
    }
    if (!current()) {
      media.getTracks().forEach((track) => track.stop());
      return null;
    }
    if (!window.MediaRecorder) {
      media.getTracks().forEach((track) => track.stop());
      stopRequestedRef.current = false;
      setError(t('unsupported'));
      return null;
    }
    setStream(media);

    const chunks: Blob[] = [];
    let recorder: MediaRecorder | undefined;
    let audio: AudioContext | undefined;
    let analyser: AnalyserNode;
    let frame = 0;
    let stopped = false;
    let settle: ((blob: Blob | null) => void) | undefined;
    const release = () => {
      stopped = true;
      cancelAnimationFrame(frame);
      media.getTracks().forEach((track) => track.stop());
      if (audio && audio.state !== 'closed') void audio.close().catch(() => {});
      if (current()) {
        cleanupRef.current = null;
        finishRef.current = null;
        stopRequestedRef.current = false;
        setStream(null);
      }
    };
    const cleanup = () => {
      if (recorder) {
        recorder.onstop = null;
        recorder.ondataavailable = null;
        recorder.onerror = null;
        if (recorder.state !== 'inactive') {
          try { recorder.stop(); } catch { /* Resources are released below. */ }
        }
      }
      release();
      settle?.(null);
    };
    cleanupRef.current = cleanup;
    try {
      recorder = new MediaRecorder(media);
      audio = new AudioContext();
      analyser = audio.createAnalyser();
      analyser.fftSize = 1024;
      audio.createMediaStreamSource(media).connect(analyser);
    } catch {
      cleanup();
      if (current()) setError(t('startFailed'));
      return null;
    }
    const activeRecorder = recorder;
    const buffer = new Float32Array(analyser.fftSize);

    let spoke = false;
    // Manual stop requests transcription even when quiet speech misses the VAD threshold.
    let forced = false;
    let partialBusy = false;
    let partialsOn = true;
    const startedAt = Date.now();
    let silenceSince = startedAt;

    // Partial transcription is optional; its failure must not prevent the final result.
    const sendPartial = async (blob: Blob) => {
      partialBusy = true;
      try {
        const form = new FormData();
        form.append('audio', blob, 'voice.webm');
        const result = await api<{ text?: string }>('/api/asr/partial', {
          method: 'POST',
          body: form,
          raw: true,
        });
        if (current() && !stopped && result.text) setPartial(result.text);
      } catch (cause) {
        if ((cause as { status?: number })?.status === 503) partialsOn = false;
      } finally {
        partialBusy = false;
      }
    };

    activeRecorder.ondataavailable = (event) => {
      if (!event.data || !event.data.size) return;
      chunks.push(event.data);
      // Include container headers by sending all chunks from the start of the recording.
      if (partialsOn && spoke && !partialBusy && activeRecorder.state === 'recording') {
        void sendPartial(new Blob(chunks, { type: 'audio/webm' }));
      }
    };

    const done = new Promise<Blob | null>((resolve) => {
      settle = resolve;
      activeRecorder.onstop = () => {
        release();
        // Keep final dataavailable before stop; obsolete phrases are discarded.
        const blob = new Blob(chunks, { type: 'audio/webm' });
        resolve(current() && (spoke || forced) && blob.size ? blob : null);
      };
      activeRecorder.onerror = () => {
        cleanup();
        if (current()) setError(t('startFailed'));
      };

      const finish = () => {
        if (activeRecorder.state !== 'inactive') {
          try { activeRecorder.stop(); } catch {
            cleanup();
            if (current()) setError(t('startFailed'));
          }
        }
      };
      finishRef.current = () => {
        forced = true;
        finish();
      };

      const tick = () => {
        if (!current() || stopped) return;
        analyser.getFloatTimeDomainData(buffer);
        let sum = 0;
        for (let i = 0; i < buffer.length; i += 1) sum += buffer[i] * buffer[i];
        const level = Math.sqrt(sum / buffer.length);
        const now = Date.now();

        if (level > VOL_SPEAK) {
          spoke = true;
          silenceSince = now;
        }
        const longEnough = now - startedAt > MIN_REC_MS;
        const quietEnough = now - silenceSince > SILENCE_MS;
        if (
          (spoke && longEnough && quietEnough) ||
          now - startedAt > REC_MAX_MS ||
          (!spoke && now - startedAt > NO_SPEECH_MS)
        ) {
          return finish();
        }
        frame = requestAnimationFrame(tick);
      };

      try { activeRecorder.start(PARTIAL_MS); } catch {
        cleanup();
        if (current()) setError(t('startFailed'));
        return;
      }
      if (stopRequestedRef.current) {
        stopRequestedRef.current = false;
        forced = true;
        finish();
      }
      if (!stopped && activeRecorder.state !== 'inactive') frame = requestAnimationFrame(tick);
    });

    const blob = await done;
    if (!blob || !current()) return null;

    setRecognizing(true);
    const form = new FormData();
    form.append('audio', blob, 'voice.webm');
    try {
      const result = await api<{ text?: string }>('/api/asr', {
        method: 'POST',
        body: form,
        raw: true,
      });
      return current() ? (result.text ?? '').trim() || null : null;
    } catch (cause) {
      // Show transcription failures instead of making the stop button appear unresponsive.
      if (current()) setError((cause as Error).message || t('recognitionFailed'));
      return null;
    } finally {
      if (current()) {
        setPartial('');
        setRecognizing(false);
      }
    }
  }, [cancel]);

  /** Stop the phrase and pass its final bytes to transcription. */
  const finish = useCallback(() => {
    if (finishRef.current) finishRef.current();
    else stopRequestedRef.current = true;
  }, []);

  return { listen, finish, cancel, stream, partial, recognizing, error };
}
