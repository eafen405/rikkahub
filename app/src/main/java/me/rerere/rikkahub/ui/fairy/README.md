# Fairy visual port

Source: Fairybot `095efdd84fd7ab2215a0b5b03369aac4f1303108`, `mobile/app/src/main/java/com/newoether/agora/`.

The drawing/motion source is copied, with package/import adaptation only:

- `ui/components/`: FairyWindow, FairyDialogPanel, FairyEmblem, FairyEyeMotion, FairyPanel, FairyScreenHud, FairyScreenPaint.
- `ui/theme/`: FairyTokens, AgoraMotionPolicy; title/HUD font declarations from Type.
- `model/FairyPresence`: only the visual enum and `isWorking`; old conversation/status aggregation is deliberately not imported. RemoteChatPanel consumes the existing shared activity and connection facts.
- `res/font/title_heavy.ttf` and `label_black.ttf`: exact copies used by the source typography.

`app/src/main/assets/licenses/FAIRYBOT_MIT.txt` retains the source license. The reply body uses rikkahub's existing Markdown/code/table/LaTeX components, not a new renderer. Device-local appearance settings remain in rikkahub's existing stores.
