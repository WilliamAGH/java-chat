# Java Chat Apple shells

`desktopApp/` and `iosApp/` are native WebKit containers for
`https://javachat.ai`. They package the existing web product; they do not
reimplement its UI, routing, authentication semantics, or data layer.

The shells inject Clerk's installed-version native OAuth transport at the
WebKit boundary. Google, LinkedIn, and Apple authorization use the system
authentication session and return through `javachat://sso-callback`; the typed
callback is then completed by the canonical Clerk client in the hosted app.

The desktop shell adds standard macOS Copy Link and Open in Default Browser
actions. The iOS target is a universal iPhone and iPad app. It follows the
installed-current simulator policy: use a compatible simulator already
installed on the Mac and identify it by UDID.

## Local unsigned builds

```sh
make -C mobile desktop
make -C mobile desktop-test
make -C mobile ios

xcrun simctl list devices booted
make -C mobile ios-test IOS_SIMULATOR_ID="<booted simulator UDID>"
```

Both projects use the same explicit `ai.javachat` bundle ID and automatic
signing under the `william@csweb.io` Apple Developer team `NB356A75PU`. The
registered App ID and the single Java Chat App Store Connect record
(`6796580187`) use `ai.javachat` for iOS, iPadOS, and macOS so the platform
builds ship as one universal purchase. Local builds and tests set
`CODE_SIGNING_ALLOWED=NO`.

## App Store distribution

```sh
make -C mobile ios-export-app-store
make -C mobile desktop-export-app-store
```

Both exports use the same bundle ID and team and target App Store Connect app
`6796580187`.

## macOS distribution

The macOS target enables App Sandbox and hardened runtime. Distribution is
bound to the `william@csweb.io` Apple Developer team configured in this project:

```sh
make -C mobile desktop-export-developer-id
make -C mobile desktop-dmg
make -C mobile desktop-notarize
```

The Developer ID lane exports a signed app suitable for direct distribution.
The DMG lane stages `JavaChat.app` beside an `Applications` shortcut for the
standard drag-to-install workflow, then signs the outer disk image with the
same `william@csweb.io` Developer ID Application identity and a secure
timestamp. The notarization lane uses the explicit `csweb` App Store Connect
profile with strict authentication to notarize and staple the app before
packaging it, then submits the signed DMG to Apple's Notary service and staples
and validates the outer ticket. Both layers therefore retain offline
notarization evidence after the app is copied out of the disk image.

These lanes require the matching Developer ID or Apple Distribution identities,
profiles, a valid `csweb` App Store Connect profile, and signed agreements for
the `william@csweb.io` account. They intentionally fail instead of falling back
to development signing or any `aventure.vc` account or profile.
