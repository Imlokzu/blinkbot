import { useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/ui/Button';
import { Field, Input, Select, Textarea } from '@/components/ui/Field';
import { Panel, PanelHead } from '@/components/ui/Panel';
import { post } from '@/lib/api';
import { t } from '@/locales/control';
import { jobBody, type Agent, type JobDraft } from './data';
import { Notice } from './ControlLayout';
import { CONTROL_KEY } from './queries';

export function NewJobForm({ agents, initialAgent, onCancel, onCreated }: {
  agents: Agent[]; initialAgent: string; onCancel: () => void; onCreated: () => void;
}) {
  const client = useQueryClient();
  const submitting = useRef(false);
  const [invalid, setInvalid] = useState(false);
  const [draft, setDraft] = useState<JobDraft>(() => ({ name: '', message: '',
    agent: agents.some((agent) => agent.id === initialAgent) ? initialAgent : agents.find((agent) => agent.default)?.id || agents[0]?.id || '',
    kind: 'daily', minutes: '60', time: '09:00', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' }));
  const save = useMutation({ mutationFn: (body: NonNullable<ReturnType<typeof jobBody>>) => post('/api/openclaw/control/jobs', body),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: [...CONTROL_KEY, 'jobs'] }); onCreated(); },
    onSettled: () => { submitting.current = false; } });
  const patch = (field: keyof JobDraft, value: string) => setDraft((old) => ({ ...old, [field]: value }));
  return <Panel>
    <PanelHead label={t('form.title')} />
    <form data-job-form="" className="space-y-4" onSubmit={(event) => {
      event.preventDefault(); if (submitting.current) return;
      const body = jobBody(draft); setInvalid(!body);
      if (body) { submitting.current = true; save.mutate(body); }
    }}>
      <fieldset disabled={save.isPending} className="min-w-0 space-y-4">
        <Field label={t('form.name')} htmlFor="job-name"><Input id="job-name" autoFocus required maxLength={120} value={draft.name} onChange={(event) => patch('name', event.target.value)} /></Field>
        <Field label={t('form.task')} htmlFor="job-task"><Textarea id="job-task" required maxLength={4000} rows={3} value={draft.message} onChange={(event) => patch('message', event.target.value)} /></Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('form.agent')} htmlFor="job-agent"><Select id="job-agent" required value={draft.agent} onChange={(event) => patch('agent', event.target.value)}>
            {agents.map((agent) => <option key={agent.id} value={agent.id}>{agent.name || agent.id}</option>)}
          </Select></Field>
          <Field label={t('form.schedule')} htmlFor="job-schedule"><Select id="job-schedule" value={draft.kind} onChange={(event) => patch('kind', event.target.value)}>
            <option value="daily">{t('form.daily')}</option><option value="every">{t('form.every')}</option>
          </Select></Field>
          {draft.kind === 'daily' ? <Field label={t('form.time')} htmlFor="job-time"><Input id="job-time" type="time" required value={draft.time} onChange={(event) => patch('time', event.target.value)} /></Field>
            : <Field label={t('form.minutes')} htmlFor="job-minutes"><Input id="job-minutes" type="number" required min={5} max={10080} step={1} value={draft.minutes} onChange={(event) => patch('minutes', event.target.value)} /></Field>}
          <Field label={t('form.timezone')} hint={t('form.timezoneHint')} htmlFor="job-timezone"><Input id="job-timezone" required maxLength={100} value={draft.timezone} onChange={(event) => patch('timezone', event.target.value)} /></Field>
        </div>
      </fieldset>
      {invalid ? <Notice error>{t('form.invalid')}</Notice> : null}
      {save.isError ? <Notice error>{t('jobs.failed')}</Notice> : null}
      <p className="max-w-[65ch] text-[12px] text-ink-3">{t('jobs.note')}</p>
      <div className="flex flex-wrap gap-2">
        <Button type="submit" variant="solid" disabled={save.isPending || !agents.length}>{save.isPending ? t('form.saving') : t('form.create')}</Button>
        <Button variant="ghost" onClick={onCancel} disabled={save.isPending}>{t('form.cancel')}</Button>
      </div>
    </form>
  </Panel>;
}
