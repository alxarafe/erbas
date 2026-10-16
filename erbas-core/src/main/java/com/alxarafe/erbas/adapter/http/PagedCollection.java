package com.alxarafe.erbas.adapter.http;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/** The closed COLLECTIONS-001 envelope and its shared HTTP pagination policy. */
public record PagedCollection<T>(List<T> items, BigInteger offset, int limit, long total, List<Order> order) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAXIMUM_LIMIT = 100;
    private static final String NONNEGATIVE_INTEGER = "[0-9]+";
    private static final List<Order> ID_ASCENDING = List.of(new Order("id", "asc"));

    public record Order(String field, String direction) { }
    public record Pagination(BigInteger offset, int limit) { }

    public static Optional<Pagination> pagination(String offset, String limit) {
        if ((offset != null && !offset.matches(NONNEGATIVE_INTEGER))
                || (limit != null && !limit.matches(NONNEGATIVE_INTEGER))) return Optional.empty();
        var appliedOffset = offset == null ? BigInteger.ZERO : new BigInteger(offset);
        var requestedLimit = limit == null ? BigInteger.valueOf(DEFAULT_LIMIT) : new BigInteger(limit);
        if (requestedLimit.signum() == 0 || requestedLimit.compareTo(BigInteger.valueOf(MAXIMUM_LIMIT)) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Pagination(appliedOffset, requestedLimit.intValueExact()));
    }

    public static <T> PagedCollection<T> of(List<T> items, Pagination pagination, long total) {
        return new PagedCollection<>(items, pagination.offset(), pagination.limit(), total, ID_ASCENDING);
    }
}
