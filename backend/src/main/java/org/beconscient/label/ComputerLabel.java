package org.beconscient.label;

import org.beconscient.common.Intervals.Span;

import java.util.UUID;

/** A stretch of computer time marked by hand: inside it, computer time takes the category. */
public record ComputerLabel(UUID id, long from, long to, UUID categoryId) {

    public Span span() {
        return new Span(from, to);
    }
}
