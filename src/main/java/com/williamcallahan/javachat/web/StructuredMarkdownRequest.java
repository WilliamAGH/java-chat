package com.williamcallahan.javachat.web;

/**
 * Request body for structured markdown processing.
 *
 * @param text The markdown text to process
 */
public record StructuredMarkdownRequest(String text) {}
