package org.eiennohito;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author eiennohito
 * @since 2016/02/19
 */
class ProcessingStep {
  private final Path data;
  private final PosixFileAttributes attrs;
  private final AtomicLong counter;
  private long ownFiles = 0;
  private long ownSize = 0;
  private long recFiles = 0;
  private long recSize = 0;

  public ProcessingStep(Path data, PosixFileAttributes attrs, AtomicLong counter) {
    this.data = data;
    this.attrs = attrs;
    this.counter = counter;
  }

  public void process(MessageFormatter formatter) throws IOException {
    long id = counter.getAndIncrement();
    try {
      formatter.appendDirectoryDown(data.getFileName().toString(), id, attrs.owner().hashCode());

      try {
        processDir(formatter, id);
      } catch (AccessDeniedException e) {
        formatter.appendError(id, e.getMessage());
      }

    } finally {
      //noinspection ThrowFromFinallyBlock
      formatter.appendDirectoryUp(id, ownSize, ownFiles, recSize, recFiles);
    }
  }

  private void processDir(MessageFormatter formatter, long id) throws IOException {
    try (DirectoryStream<Path> contents = Files.newDirectoryStream(data)) {
      for (Path p : contents) {
        PosixFileAttributes childAttrs;
        try {
          childAttrs = Files.readAttributes(p, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

          if (childAttrs.isDirectory() && !childAttrs.isSymbolicLink()) {
            ProcessingStep child = new ProcessingStep(p, childAttrs, counter);
            addUp(child);
          } else {
            ownFiles += childAttrs.size();
            ownSize += 1;
          }
        } catch (SecurityException e) {
          formatter.appendError(id, "security exception: " + p.getFileName() + "\n" + e.getMessage());
        }
      }
    }
    recSize += ownSize;
    recFiles += ownFiles;
  }

  private void addUp(ProcessingStep child) {
    recFiles += child.recFiles;
    recSize += child.recSize;
  }
}
