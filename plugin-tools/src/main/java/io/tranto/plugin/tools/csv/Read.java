package io.tranto.plugin.tools.csv;

import io.tranto.core.models.annotations.Example;
import io.tranto.core.models.annotations.Plugin;
import io.tranto.core.models.annotations.PluginProperty;
import io.tranto.core.models.tasks.Output;
import io.tranto.core.models.tasks.RunnableTask;
import io.tranto.core.models.tasks.Task;
import io.tranto.core.runners.RunContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Parses CSV text into rows. With {@code header: true} (the default) the first record supplies the
 * column names and every row is emitted as an ordered {@code Map<String, String>}; otherwise each
 * row is a {@code List<String>}.
 *
 * <p>The parser follows RFC&nbsp;4180: fields may be quoted with {@code "}, quotes inside a quoted
 * field are escaped by doubling ({@code ""}), and quoted fields may contain the delimiter and
 * newlines. The {@code delimiter} defaults to {@code ,}. No external dependency — pure JDK.</p>
 */
@SuperBuilder(toBuilder = true)
@Getter
@NoArgsConstructor
@Plugin(
    title = "Parse CSV text into rows",
    examples = @Example(
        title = "Parse a semicolon-separated file's contents into header-keyed rows",
        code = {
            "id: rows",
            "type: io.tranto.plugin.tools.csv.Read",
            "from: \"{{ outputs.read.content }}\"",
            "delimiter: \";\""
        }
    )
)
public class Read extends Task implements RunnableTask<Read.CsvOutput> {

    /** The CSV text to parse. Pebble-rendered before parsing. */
    @PluginProperty(dynamic = true)
    private String from;

    /** Treat the first record as the header row. Defaults to {@code true}. */
    @PluginProperty
    private Boolean header;

    /** The field delimiter. Defaults to {@code ,}. Must be a single character. */
    @PluginProperty
    private String delimiter;

    @Override
    public CsvOutput run(final RunContext runContext) throws Exception {
        if (from == null) {
            throw new IllegalArgumentException("Read requires a non-null 'from' value");
        }
        final String rendered = runContext.render(from);
        final boolean hasHeader = header == null || header;
        final char delim = resolveDelimiter();

        final List<List<String>> records = parse(rendered, delim);

        if (records.isEmpty()) {
            return new CsvOutput(List.of(), List.of(), 0);
        }

        if (!hasHeader) {
            // Each row is the raw field list.
            return new CsvOutput(List.copyOf(records), List.of(), records.size());
        }

        final List<String> columns = records.get(0);
        final List<Map<String, String>> rows = new ArrayList<>(records.size() - 1);
        for (int i = 1; i < records.size(); i++) {
            final List<String> fields = records.get(i);
            final Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < columns.size(); c++) {
                row.put(columns.get(c), c < fields.size() ? fields.get(c) : "");
            }
            rows.add(row);
        }
        runContext.logger().debug("Parsed {} data row(s) with {} column(s)", rows.size(), columns.size());
        return new CsvOutput(rows, columns, rows.size());
    }

    private char resolveDelimiter() {
        if (delimiter == null || delimiter.isEmpty()) {
            return ',';
        }
        if (delimiter.length() != 1) {
            throw new IllegalArgumentException("'delimiter' must be a single character, got '" + delimiter + "'");
        }
        return delimiter.charAt(0);
    }

    /**
     * RFC 4180 parser. Handles quoted fields, doubled-quote escapes, embedded delimiters/newlines,
     * and both {@code \n} and {@code \r\n} line endings. A trailing newline does not produce a
     * spurious empty record.
     */
    private static List<List<String>> parse(final String input, final char delim) {
        final List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;

        final int n = input.length();
        for (int i = 0; i < n; i++) {
            final char ch = input.charAt(i);

            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }

            if (ch == '"') {
                inQuotes = true;
                fieldStarted = true;
            } else if (ch == delim) {
                current.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < n && input.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
                fieldStarted = false;
            } else {
                field.append(ch);
                fieldStarted = true;
            }
        }

        // Flush the final field/record unless the input ended exactly on a record boundary.
        if (fieldStarted || field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }

    /**
     * @param rows    the parsed rows: {@code Map<String,String>} per row when a header is present,
     *                otherwise {@code List<String>} per row
     * @param columns the header column names (empty when {@code header: false})
     * @param count   the number of data rows
     */
    public record CsvOutput(List<?> rows, List<String> columns, int count) implements Output {
    }
}
