# Every lane runs on the Mac build host. Any other host (the laptop, Linux peers) forwards the goals
# and command-line variables, each shell-quoted, to a warm mirror of this checkout there through the
# host tool remote-slot (dotfiles, ~/.local/bin). The environment, gitignored files, and make's own
# flags do not cross, and every output (installs, archives, receipts) stays on that host. GitHub
# Actions jobs already run on the Mac their workflow picked.
APPLE_BUILD_HOST := haiku-7
# haiku-7 is the only Apple build host: an override sent builds to haiku-50, the user's Mac.
# GitHub Actions names its own runner, which is haiku-7.
$(if $(and $(filter command line,$(origin APPLE_BUILD_HOST)),$(if $(GITHUB_ACTIONS),,1)),$(error APPLE_BUILD_HOST=$(APPLE_BUILD_HOST) refused: Apple lanes build only on haiku-7, so drop the override and this lane forwards there through remote-slot))
ifeq ($(filter $(APPLE_BUILD_HOST),$(shell hostname -s))$(GITHUB_ACTIONS),)
APPLE_BUILD_HOST_VARIABLES := $(strip $(foreach variable,$(sort $(.VARIABLES)),$(if $(filter command line,$(origin $(variable))),'$(variable)=$(subst ','\'',$(value $(variable)))')))
.DEFAULT_GOAL := apple-build-host
.PHONY: apple-build-host $(MAKECMDGOALS)
apple-build-host:
	remote-slot run $(APPLE_BUILD_HOST) -- make $(MAKECMDGOALS) $(APPLE_BUILD_HOST_VARIABLES)
ifneq ($(MAKECMDGOALS),)
$(MAKECMDGOALS): apple-build-host ; @:
endif
else

.PHONY: \
	signing-session \
	desktop \
	desktop-test \
	desktop-archive \
	desktop-export-developer-id \
	desktop-create-signed-dmg \
	desktop-dmg \
	desktop-notarize \
	desktop-export-app-store \
	ios \
	ios-test \
	ios-archive \
	ios-export-app-store

# Every xcodebuild lane compiles through Xcode's shared per-user compilation cache.
export XCODE_XCCONFIG_FILE := $(CURDIR)/compilation-cache.xcconfig

DEVELOPMENT_TEAM := NB356A75PU
ASC_PROFILE := csweb
DEVELOPER_ID_APPLICATION := Developer ID Application: William Callahan (NB356A75PU)
DESKTOP_DISTRIBUTION_DIR := desktopApp/distribution
DESKTOP_ARCHIVE := $(DESKTOP_DISTRIBUTION_DIR)/JavaChat.xcarchive
DESKTOP_DEVELOPER_ID_EXPORT := $(DESKTOP_DISTRIBUTION_DIR)/developer-id
DESKTOP_NOTARIZATION_ZIP := $(DESKTOP_DISTRIBUTION_DIR)/JavaChat.zip
DESKTOP_DMG_STAGING := $(DESKTOP_DISTRIBUTION_DIR)/dmg
DESKTOP_DMG_STAGED_APP := $(DESKTOP_DMG_STAGING)/JavaChat.app
DESKTOP_APP_STORE_EXPORT := $(DESKTOP_DISTRIBUTION_DIR)/app-store
DESKTOP_DMG := $(DESKTOP_DISTRIBUTION_DIR)/JavaChat.dmg
IOS_DISTRIBUTION_DIR := iosApp/distribution
IOS_ARCHIVE := $(IOS_DISTRIBUTION_DIR)/JavaChat.xcarchive
IOS_APP_STORE_EXPORT := $(IOS_DISTRIBUTION_DIR)/app-store

# Signed lanes unlock this Mac's build keychain through the host tool build-keychain (dotfiles,
# ~/.local/bin), so they run from a fresh SSH session with no console login [HH0a]; a Mac without
# one signs from its open login keychain. CI never runs a signed lane.
signing-session:
	@build-keychain unlock

desktop:
	xcodebuild \
		-project desktopApp/JavaChatDesktop.xcodeproj \
		-scheme JavaChatDesktop \
		-configuration Debug \
		-destination 'platform=macOS,arch=arm64' \
		-derivedDataPath desktopApp/build \
		build CODE_SIGNING_ALLOWED=NO

desktop-test:
	xcodebuild \
		-project desktopApp/JavaChatDesktop.xcodeproj \
		-scheme JavaChatDesktop \
		-configuration Debug \
		-destination 'platform=macOS,arch=arm64' \
		-derivedDataPath desktopApp/build-test \
		test CODE_SIGNING_ALLOWED=NO

desktop-archive: signing-session
	xcodebuild \
		-project desktopApp/JavaChatDesktop.xcodeproj \
		-scheme JavaChatDesktop \
		-configuration Release \
		-destination 'generic/platform=macOS' \
		-archivePath $(DESKTOP_ARCHIVE) \
		-allowProvisioningUpdates \
		archive DEVELOPMENT_TEAM="$(DEVELOPMENT_TEAM)"

desktop-export-developer-id: desktop-archive
	xcodebuild \
		-exportArchive \
		-archivePath $(DESKTOP_ARCHIVE) \
		-exportPath $(DESKTOP_DEVELOPER_ID_EXPORT) \
		-exportOptionsPlist desktopApp/ExportOptions-DeveloperID.plist \
		-allowProvisioningUpdates
	codesign --verify --deep --strict --verbose=2 \
		$(DESKTOP_DEVELOPER_ID_EXPORT)/JavaChat.app
	codesign -dvvv $(DESKTOP_DEVELOPER_ID_EXPORT)/JavaChat.app 2>&1 | \
		rg --quiet 'Authority=Developer ID Application: William Callahan \(NB356A75PU\)'
	codesign -dvvv $(DESKTOP_DEVELOPER_ID_EXPORT)/JavaChat.app 2>&1 | \
		rg --quiet '^Timestamp='

desktop-dmg: desktop-export-developer-id
	$(MAKE) desktop-create-signed-dmg

desktop-create-signed-dmg: signing-session
	rm -rf "$(DESKTOP_DMG_STAGING)"
	mkdir -p "$(DESKTOP_DMG_STAGING)"
	ditto \
		"$(DESKTOP_DEVELOPER_ID_EXPORT)/JavaChat.app" \
		"$(DESKTOP_DMG_STAGED_APP)"
	ln -s /Applications "$(DESKTOP_DMG_STAGING)/Applications"
	hdiutil create \
		-volname "Java Chat" \
		-srcfolder "$(DESKTOP_DMG_STAGING)" \
		-ov \
		-format UDZO \
		$(DESKTOP_DMG)
	codesign --force \
		--timestamp \
		--sign "$(DEVELOPER_ID_APPLICATION)" \
		$(DESKTOP_DMG)
	codesign --verify --strict --verbose=2 $(DESKTOP_DMG)

desktop-notarize: desktop-export-developer-id
	ditto -c -k --keepParent \
		$(DESKTOP_DEVELOPER_ID_EXPORT)/JavaChat.app \
		$(DESKTOP_NOTARIZATION_ZIP)
	asc --strict-auth --profile $(ASC_PROFILE) notarization submit \
		--file $(DESKTOP_NOTARIZATION_ZIP) \
		--wait
	xcrun stapler staple $(DESKTOP_DEVELOPER_ID_EXPORT)/JavaChat.app
	xcrun stapler validate $(DESKTOP_DEVELOPER_ID_EXPORT)/JavaChat.app
	$(MAKE) desktop-create-signed-dmg
	asc --strict-auth --profile $(ASC_PROFILE) notarization submit \
		--file $(DESKTOP_DMG) \
		--wait
	xcrun stapler staple $(DESKTOP_DMG)
	xcrun stapler validate $(DESKTOP_DMG)

desktop-export-app-store: desktop-archive
	xcodebuild \
		-exportArchive \
		-archivePath $(DESKTOP_ARCHIVE) \
		-exportPath $(DESKTOP_APP_STORE_EXPORT) \
		-exportOptionsPlist desktopApp/ExportOptions-AppStore.plist \
		-allowProvisioningUpdates

ios:
	xcodebuild \
		-project iosApp/JavaChat.xcodeproj \
		-scheme JavaChat \
		-configuration Debug \
		-destination 'generic/platform=iOS Simulator' \
		-derivedDataPath iosApp/build \
		build CODE_SIGNING_ALLOWED=NO

ios-test:
	@test -n "$(IOS_SIMULATOR_ID)" || \
		(echo "Set IOS_SIMULATOR_ID to an installed, booted simulator UDID." >&2; exit 2)
	xcodebuild \
		-project iosApp/JavaChat.xcodeproj \
		-scheme JavaChat \
		-configuration Debug \
		-destination 'platform=iOS Simulator,id=$(IOS_SIMULATOR_ID)' \
		-derivedDataPath iosApp/build-test \
		test CODE_SIGNING_ALLOWED=NO

ios-archive: signing-session
	xcodebuild \
		-project iosApp/JavaChat.xcodeproj \
		-scheme JavaChat \
		-configuration Release \
		-destination 'generic/platform=iOS' \
		-archivePath $(IOS_ARCHIVE) \
		-allowProvisioningUpdates \
		archive DEVELOPMENT_TEAM="$(DEVELOPMENT_TEAM)"

ios-export-app-store: ios-archive
	xcodebuild \
		-exportArchive \
		-archivePath $(IOS_ARCHIVE) \
		-exportPath $(IOS_APP_STORE_EXPORT) \
		-exportOptionsPlist iosApp/ExportOptions-AppStore.plist \
		-allowProvisioningUpdates
endif
