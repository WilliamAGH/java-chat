package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataHolder;

public class InlineListExtension implements Parser.ParserExtension {
    @Override
    public void extend(Parser.Builder builder) {
        // No-op in this build; list normalization handled via DOM after render.
    }

    @Override
    public void parserOptions(MutableDataHolder options) {
        // No options
    }

    public static com.vladsch.flexmark.util.misc.Extension create() { return new InlineListExtension(); }
}
