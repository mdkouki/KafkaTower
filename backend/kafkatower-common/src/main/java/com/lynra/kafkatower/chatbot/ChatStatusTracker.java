package com.lynra.kafkatower.chatbot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/*
 * Per-request status state, shared by the request thread and any virtual thread spawned from it
 * (parallel tool loop), hence every method is synchronized. The label pushed to the browser is
 * always the most specific thing still running: the latest running specialist, else the root's
 * current phase. Without that, a finished specialist's label stayed on screen while the root was
 * already composing or reviewing the answer.
 */
final class ChatStatusTracker {

    private final Consumer<String> sink;
    private final Map<Object, String> runningSpecialistLabels = new LinkedHashMap<>();
    private String phaseLabel;
    private boolean rootSeen;
    private boolean closed;

    ChatStatusTracker(Consumer<String> sink) {
        this.sink = sink;
    }

    synchronized void phaseStarted(String label) {
        phaseLabel = label;
        if (runningSpecialistLabels.isEmpty()) {
            push(label);
        }
    }

    synchronized void processCreated(Object processId, Supplier<String> specialistLabel) {
        if (!rootSeen) {
            // The first creation event of a request is the root process, which gets no label.
            rootSeen = true;
            return;
        }
        String label = specialistLabel.get();
        runningSpecialistLabels.put(processId, label);
        push(label);
    }

    synchronized void processEnded(Object processId) {
        if (runningSpecialistLabels.remove(processId) == null) {
            return;
        }
        String label = currentLabel();
        if (label != null) {
            push(label);
        }
    }

    synchronized void close() {
        closed = true;
    }

    private String currentLabel() {
        return runningSpecialistLabels.values().stream().reduce((earlier, later) -> later).orElse(phaseLabel);
    }

    private void push(String label) {
        if (!closed) {
            sink.accept(AgentStatusEventListener.STATUS_PREFIX + label);
        }
    }
}
