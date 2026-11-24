package com.lynra.kafkatower.collections.bulk;

import java.util.List;
import java.util.Map;

public sealed interface BulkResult {

    record Items(List<String> items) implements BulkResult {
    }

    record Everything(List<String> items) implements BulkResult {
    }

    record Value(String text) implements BulkResult {
    }

    record Groups(Map<String, List<String>> groups) implements BulkResult {
    }
}
