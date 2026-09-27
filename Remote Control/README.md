# Remote Control — USB remote (2.4G Composite Device)

Listener for a wireless USB remote/receiver (`lsusb`: `0627:697d Adomax
Technology 2.4G Composite Device`), connected to Raspberry Pi 3. Registers
in the system as three under-devices:

- `event2` — keyboard part
- `event4` — Consumer Control (volume, mute, media buttons)
- `event5` — System Control (power/sleep)

## What `remote_listener.py` does

1. Prints **every** button press into the console (`raw code=...`) — to
   collect the button map of a specific remote for future use
   (which button on the remote corresponds to which keycode).
2. Immediately maps standard volume buttons (`KEY_VOLUMEUP`/`KEY_VOLUMEDOWN`/
   `KEY_MUTE`) to real `amixer` commands — volume can already be controlled
   by the remote right now.

## Installation and launch on Pi

```bash
pip install evdev --break-system-packages   # or in Vision Agent venv
python3 remote_listener.py
```

The user must be in the `input` group (check: `groups`), otherwise `/dev/input/eventN`
are inaccessible without sudo.

## What's next

Buttons unknown to the script (everything except volume) are currently only printed to
the console — having collected the keycodes of this remote's real buttons, they can be mapped
to other actions (e.g. talking/listening toggle for Voice Loop, navigating through
tabs in Device Setup Wizard, etc.).
