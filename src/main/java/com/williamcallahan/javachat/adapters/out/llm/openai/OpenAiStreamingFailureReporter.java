package com.williamcallahan.javachat.adapters.out.llm.openai;

import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter;
import org.springframework.stereotype.Component;

/** Reports OpenAI-compatible terminal failures with bounded provider diagnostics. */
@Component
public final class OpenAiStreamingFailureReporter implements StreamingFailureReporter {

    /** Reports one terminal provider failure and returns the exception to propagate. */
    @Override
    public RuntimeException reportTerminalFailure(Throwable upstreamFailure, TerminalAttempt terminalAttempt) {
        return OpenAiStreamingFailureException.terminalAndLog(upstreamFailure, terminalAttempt);
    }
}
