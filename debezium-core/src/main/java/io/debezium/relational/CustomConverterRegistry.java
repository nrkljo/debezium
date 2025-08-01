/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.relational;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.connect.data.SchemaBuilder;

import io.debezium.annotation.GuardedBy;
import io.debezium.annotation.Immutable;
import io.debezium.annotation.ThreadSafe;
import io.debezium.service.Service;
import io.debezium.spi.converter.ConvertedField;
import io.debezium.spi.converter.CustomConverter;
import io.debezium.spi.converter.RelationalColumn;

/**
 * The registry of all converters that were provided by the connector configuration.
 *
 * @author Jiri Pechanec
 *
 */
@ThreadSafe
public class CustomConverterRegistry implements Service {

    @Immutable
    private final List<CustomConverter<SchemaBuilder, ConvertedField>> converters;

    @GuardedBy("registerConverterFor")
    private final Map<String, ConverterDefinition<SchemaBuilder>> conversionFunctionMap = new HashMap<>();

    public CustomConverterRegistry(List<CustomConverter<SchemaBuilder, ConvertedField>> converters) {
        if (converters == null) {
            this.converters = Collections.emptyList();
        }
        else {
            this.converters = Collections.unmodifiableList(converters);
        }
    }

    /**
     * Create and register a converter for a given database column.
     *
     * @param table the table that contains the column
     * @param column the column metadata
     * @return the schema of the value generated by the converter or empty if converter does not support the column
     */
    public synchronized Optional<SchemaBuilder> registerConverterFor(TableId table, Column column, Object defaultValue) {
        final String fullColumnName = fullColumnName(table, column);

        for (CustomConverter<SchemaBuilder, ConvertedField> customConverter : converters) {
            AtomicReference<ConverterDefinition<SchemaBuilder>> definition = new AtomicReference<>();
            customConverter.converterFor(new RelationalColumn() {

                @Override
                public String name() {
                    return column.name();
                }

                @Override
                public String dataCollection() {
                    return table.toString();
                }

                @Override
                public String typeName() {
                    return column.typeName();
                }

                @Override
                public String typeExpression() {
                    return column.typeExpression();
                }

                @Override
                public OptionalInt scale() {
                    return column.scale().isPresent() ? OptionalInt.of(column.scale().get()) : OptionalInt.empty();
                }

                @Override
                public int nativeType() {
                    return column.nativeType();
                }

                @Override
                public OptionalInt length() {
                    return column.length() == Column.UNSET_INT_VALUE ? OptionalInt.empty() : OptionalInt.of(column.length());
                }

                @Override
                public int jdbcType() {
                    return column.jdbcType();
                }

                @Override
                public boolean isOptional() {
                    return column.isOptional();
                }

                @Override
                public boolean hasDefaultValue() {
                    return column.hasDefaultValue();
                }

                @Override
                public Object defaultValue() {
                    return defaultValue;
                }

                @Override
                public String charsetName() {
                    return column.charsetName();
                }
            }, (fieldSchema, converter) -> definition.set(new ConverterDefinition<>(fieldSchema, converter)));

            if (definition.get() != null) {
                conversionFunctionMap.put(fullColumnName, definition.get());
                return Optional.of(definition.get().fieldSchema);
            }
        }

        // Remove in case the table was altered and converter is no longer valid
        conversionFunctionMap.remove(fullColumnName);
        return Optional.empty();
    }

    /**
     * Obtain a pre-registered converter for a given column.
     *
     * @param table the table that contains the column
     * @param column the column metadata
     * @return the the value converter or empty if converter does not support the column
     */
    public Optional<ValueConverter> getValueConverter(TableId table, Column column) {
        final ConverterDefinition<SchemaBuilder> converterDefinition = conversionFunctionMap.get(fullColumnName(table, column));
        if (converterDefinition == null) {
            return Optional.empty();
        }
        return Optional.of(x -> converterDefinition.converter.convert(x));
    }

    /**
     * @return true if no custom converters will be used by the connector
     */
    public boolean isEmpty() {
        return conversionFunctionMap.isEmpty();
    }

    private String fullColumnName(TableId table, Column column) {
        return table + "." + column.name();
    }

    /**
     * Class binding together the schema of the conversion result and the converter code.
     *
     * @param <S> schema describing the output type, usually {@link org.apache.kafka.connect.data.SchemaBuilder}
     */
    public static class ConverterDefinition<S> {
        public final S fieldSchema;
        public final CustomConverter.Converter converter;

        public ConverterDefinition(S fieldSchema, CustomConverter.Converter converter) {
            this.fieldSchema = fieldSchema;
            this.converter = converter;
        }
    }
}
