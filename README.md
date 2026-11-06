# NEXUS Desktop (v0.1, prototype)

DeX-like desktop windows on a second screen, for phones without Samsung DeX
(target: vivo X200 Pro, OriginOS 7; the device reports Android 17 / SDK 37). Needs Shizuku (no PC required).

## How it works
1. A Shizuku "user service" runs as shell uid 2000 and creates a trusted **VirtualDisplay**
   whose output Surface belongs to our app's `SurfaceView`. The GPU composites straight into
   the view: no screen capture, no video encode.
2. Apps are started on that display with `am start --display <id>`.
3. Touch / mouse / keyboard received by the `SurfaceView` window are re-injected into
   that display with `InputManager.injectInputEvent` after `setDisplayId`.
4. The display's IME policy is set to "fallback display", so the on-screen keyboard shows on
   the real screen while you type into an app living on the virtual display.

## Install (phone only)
1. Install Shizuku, enable Developer options -> Wireless debugging, start Shizuku from its app.
2. Install FreeDesk APK (from the GitHub Releases page of your repo).
3. Open FreeDesk -> "Grant Shizuku permission" -> "Run diagnostics" -> "Copy report".
4. Try "Open desktop on THIS screen (test)" -> Apps -> pick an app.
   With a monitor connected, use the "Open desktop on display #N" button instead.

## What to report back
* The diagnostics text (Copy report).
* Which apps open / do not open on the virtual display.
* Whether Thai typing works (a) by default, (b) with the `EN/TH` toggle (Caps Lock or button).
* Whether the virtual display feels 60 Hz or faster. The diagnostics line `refresh:` says
  whether the ROM accepted a requested rate.

## Known unknowns (not testable without the device)
* OriginOS may reject some virtual-display flags (the service falls back through 3 tiers).
* `TH` mode pastes characters through the clipboard; it overwrites your clipboard.
* Mouse buttons / hover on the virtual display are forwarded but not stamped like a real mouse.

## v0.1 additions beyond DeskPort
* **Secure / DRM mode** (checkbox on main screen): creates the virtual display with the SECURE flag and
  marks the SurfaceView + window secure. Diagnostics line `secure/DRM:` says whether the ROM accepted it.
  Widevine L1 (Netflix HD, etc.) may still stay black: a virtual display is not an HDCP-protected output.
* **Game mode** (taskbar button, or Ctrl+Alt+G): captures the physical mouse and forwards raw relative
  deltas, so the cursor hides and games that use pointer capture can look around. Also exits with Ctrl+Alt+G.
