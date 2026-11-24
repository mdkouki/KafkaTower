package com.lynra.kafkatower.collections.bulk;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BulkOperationRegistry {

    private final Map<String, BulkOperation> operationsByName;

    public BulkOperationRegistry(List<BulkOperation> operations) {
        this.operationsByName = operations.stream()
                .collect(Collectors.toMap(BulkOperation::name, Function.identity()));
    }

    public Optional<BulkOperation> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(operationsByName.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    public List<String> names() {
        return operationsByName.keySet().stream().sorted().toList();
    }
}
