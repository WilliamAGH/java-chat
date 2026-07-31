.PHONY: \
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

desktop-archive:
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

desktop-create-signed-dmg:
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

ios-archive:
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
