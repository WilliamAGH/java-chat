File I/O crosses the boundary between your program and an external filesystem. Model a location with `Path`, perform operations through `Files`, select a charset deliberately for text, and let checked `IOException` make failure handling visible.

## Prerequisites

- Exceptions and try-with-resources.
- Strings and collections.

## Read, write, and close deliberately

This self-contained program writes a temporary UTF-8 note, reads it back, counts its lines through a resource-owning stream, and removes the temporary file in `finally`.

```java
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class FileIo {
    private static final String LESSON_NOTES = "Collections\nGenerics\n";

    public static void main(String[] arguments) throws IOException {
        Path lessonNotesPath = Files.createTempFile("lesson-notes-", ".txt");

        try {
            Files.writeString(lessonNotesPath, LESSON_NOTES, StandardCharsets.UTF_8);
            String savedNotes = Files.readString(lessonNotesPath, StandardCharsets.UTF_8);

            long lineCount;
            try (Stream<String> noteLines = Files.lines(lessonNotesPath, StandardCharsets.UTF_8)) {
                lineCount = noteLines.count();
            }

            System.out.println(savedNotes.strip());
            System.out.println("Lines: " + lineCount);
        } finally {
            Files.deleteIfExists(lessonNotesPath);
        }
    }
}
```

Compile and run it with `javac FileIo.java` and `java FileIo`. The explicit `UTF_8` argument records the file format in the program. `Files.readString(path)` and `Files.writeString(path, text)` also default to UTF-8 in Java 25, but an explicit charset is useful when text crosses a system boundary.

## Separate a path from an operation

`Path.of("notes", "week-one.txt")` creates a path value; it does not create a file or prove that one exists. `Files` performs the actual operation and can fail because of permissions, missing parents, concurrent changes, storage limits, or provider-specific behavior.

Whole-file methods such as `readString` and `writeString` open and close the file internally; use them for bounded, small text. For large content, process incrementally with a reader or stream so the program does not need to hold the entire file in memory. A stream returned by `Files.lines` owns a live file resource, so it must be closed with try-with-resources.

Create parent directories before writing a nested new path with `Files.createDirectories(parentPath)`. Do not use a pre-check such as `Files.exists` as proof that a later operation will succeed: the filesystem can change between the check and the operation. Handle the operation's actual exception instead.

## Treat encoding and cleanup as part of the contract

Text is bytes plus a charset. A file written in UTF-8 must be read as UTF-8 unless its format says otherwise. Never rely on a machine's default charset for an interoperable file format.

Garbage collection does not provide timely resource cleanup. A stream from `Files.lines`, a network stream, or a database connection belongs in a try-with-resources block so it closes at the end of the lexical scope even if reading or parsing fails.

## Common misconceptions

- **“`Path` is an open file.”** No. It is a location value. `Files` performs the I/O.
- **“A stream always fits in memory.”** A stream can process values lazily; `Files.readString` intentionally reads the whole file.
- **“Every `Files` call needs try-with-resources.”** No. Whole-file methods return completed values after closing internally; `Files.lines` returns a live stream that must be closed.
- **“The garbage collector will close the file soon enough.”** Resource lifetime must be explicit; use try-with-resources.

## Practice prompts

1. Write a `Path.of("notes", "week-one.txt")` file after creating its parent directory.
2. Read a UTF-8 file line by line and count only nonblank lesson titles.
3. Change the text to include a non-ASCII character, then verify that explicit UTF-8 preserves it after the round trip.

Read the Java 25 [`Path`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Path.html) and [`Files`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html) APIs for the exact options and exception contracts.
