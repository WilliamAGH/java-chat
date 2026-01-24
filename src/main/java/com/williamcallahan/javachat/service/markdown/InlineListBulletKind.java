package com.williamcallahan.javachat.service.markdown;

/**
 * Bullet marker characters recognized by the inline list parser.
 */
enum InlineListBulletKind {
    DASH('-'),
    ASTERISK('*'),
    PLUS('+'),
    BULLET('\u2022');

    private final char markerChar;

    InlineListBulletKind(char markerChar) {
        this.markerChar = markerChar;
    }

    char markerChar() {
        return markerChar;
    }
}
