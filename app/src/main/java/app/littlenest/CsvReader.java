package app.littlenest;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

final class CsvReader implements AutoCloseable {
    private final PushbackReader input;
    private int row;
    CsvReader(Reader input) throws IOException {
        this.input = new PushbackReader(input, 1);
        int first = this.input.read();
        if (first != -1 && first != '\ufeff') this.input.unread(first);
    }
    List<String> next() throws IOException {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false, started = false;
        int count = 0;
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (quoted) throw invalid("Unterminated quoted field");
                if (!started && cells.isEmpty() && cell.length() == 0) return null;
                cells.add(cell.toString()); row++; return cells;
            }
            if (++count > 2_000_000) throw invalid("CSV record exceeds 2 MB");
            char ch = (char) value;
            if (quoted) {
                if (ch == '"') {
                    int next = input.read();
                    if (next == '"') cell.append('"');
                    else { quoted = false; closed = true; if (next != -1) input.unread(next); }
                } else cell.append(ch);
                continue;
            }
            if (ch == ',' || ch == '\n' || ch == '\r') {
                cells.add(cell.toString()); cell.setLength(0); closed = false; started = true;
                if (cells.size() > 300) throw invalid("Too many CSV columns");
                if (ch != ',') {
                    if (ch == '\r') { int next = input.read(); if (next != -1 && next != '\n') input.unread(next); }
                    row++; return cells;
                }
            } else {
                if (closed) throw invalid("Unexpected text after closing quote");
                if (ch == '"') {
                    if (cell.length() != 0) throw invalid("Quote inside an unquoted field");
                    quoted = true;
                } else cell.append(ch);
                started = true;
            }
        }
    }
    private IOException invalid(String message) { return new IOException("CSV record " + (row + 1) + ": " + message); }
    @Override public void close() throws IOException { input.close(); }
}
