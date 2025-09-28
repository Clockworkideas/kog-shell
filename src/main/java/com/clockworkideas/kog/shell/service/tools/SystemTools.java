package com.clockworkideas.kog.shell.service.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
@Component
@RequiredArgsConstructor
public class SystemTools {

    private String currentDirectory=System.getProperty("user.dir");


    @Tool(description="Get current system date time")
    public String getCurrentDateTimeLocal(){
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "EEEE, MMMM d, yyyy hh:mm:ss.SSS a"
        );
        return LocalDateTime.now().format(formatter);
    }

    @Tool(description="set system user directory")
    public String setCurrentDirectory(String newDirectory){
        return changeDirectory(newDirectory);
    }

    @Tool(description = "get system user directory")
    public String getCurrentDirectory(){
        return this.currentDirectory;
    }

    @Tool(description = "List files and directories in the current working directory")
    public String listCurrentDirectory() {
        try {
            StringBuilder result = new StringBuilder();
            Files.list(Paths.get(currentDirectory))
                    .forEach(path -> {
                        if (Files.isDirectory(path)) {
                            result.append("[DIR]  ").append(path.getFileName()).append("\n");
                        } else {
                            result.append("       ").append(path.getFileName()).append("\n");
                        }
                    });
            return result.toString().isEmpty() ? "Directory is empty" : result.toString();
        } catch (IOException e) {
            return "Error reading directory: " + e.getMessage();
        }
    }

    @Tool(description = "Change current working directory (validated like 'cd')")
    public String changeDirectory(String newPath) {
        try {
            // Expand ~ and environment variables without invoking a shell
            String expanded = expandPath(newPath);

            Path base = Paths.get(currentDirectory);
            Path candidate = Paths.get(expanded);

            // Support relative targets (e.g., ".", "..", "subdir")
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : base.resolve(candidate).normalize();

            if (!Files.exists(resolved)) {
                return "Path does not exist: " + resolved.toAbsolutePath();
            }
            if (!Files.isDirectory(resolved)) {
                return "Not a directory: " + resolved.toAbsolutePath();
            }
            // Basic permission checks for usability
            if (!Files.isReadable(resolved)) {
                return "Directory is not readable: " + resolved.toAbsolutePath();
            }
            if (!Files.isExecutable(resolved)) {
                return "Directory is not traversable (no execute permission): " + resolved.toAbsolutePath();
            }

            this.currentDirectory = resolved.toAbsolutePath().toString();
            return "Changed directory to " + this.currentDirectory;
        } catch (Exception e) {
            return "Failed to change directory: " + e.getMessage();
        }
    }

    protected String expandPath(String input) {
        if (input == null || input.isBlank()) return input;

        String s = input;

        // ~ or ~/something
        if (s.equals("~") || s.startsWith("~/")) {
            s = System.getProperty("user.home") + s.substring(1);
        }

        // $VAR and ${VAR}
        Map<String, String> env = System.getenv();

        // ${VAR}
        Pattern brace = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
        Matcher m1 = brace.matcher(s);
        StringBuffer sb1 = new StringBuffer();
        while (m1.find()) {
            String val = env.getOrDefault(m1.group(1), "");
            m1.appendReplacement(sb1, Matcher.quoteReplacement(val));
        }
        m1.appendTail(sb1);
        s = sb1.toString();

        // $VAR
        Pattern bare = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");
        Matcher m2 = bare.matcher(s);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            String val = env.getOrDefault(m2.group(1), "");
            m2.appendReplacement(sb2, Matcher.quoteReplacement(val));
        }
        m2.appendTail(sb2);

        return sb2.toString();
    }

    @Tool(description = "Read the contents of a file in the current working directory")
    public String readFile(String fileName) {
        try {
            // Resolve relative to currentDirectory
            Path filePath = Paths.get(currentDirectory).resolve(fileName).normalize();

            if (!Files.exists(filePath)) {
                return "File does not exist: " + filePath.toAbsolutePath();
            }
            if (Files.isDirectory(filePath)) {
                return "Path is a directory, not a file: " + filePath.toAbsolutePath();
            }
            if (!Files.isReadable(filePath)) {
                return "File is not readable: " + filePath.toAbsolutePath();
            }

            // Read all content (UTF-8 assumed)
            return Files.readString(filePath);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }


    @Tool(description = "Remove a file or directory (recursive). Only paths under the current working directory are allowed.")
    public String removePath(String target) {
        try {
            if (target == null || target.isBlank()) {
                return "No path provided.";
            }

            // Expand ~ and $VARs (your expandPath method), then resolve against currentDirectory
            String expanded = expandPath(target);
            Path base = Paths.get(currentDirectory).toAbsolutePath().normalize();
            Path candidate = Paths.get(expanded);
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : base.resolve(candidate).normalize();

            // Safety: only allow deleting within currentDirectory subtree
            if (!resolved.startsWith(base)) {
                return "Refusing to delete outside current working directory: " + resolved;
            }

            if (!Files.exists(resolved)) {
                return "Path does not exist: " + resolved.toAbsolutePath();
            }

            // If it's a directory, delete recursively (do not follow directory symlinks)
            final DeletionCounter counter = new DeletionCounter();
            if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(resolved, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        try {
                            Files.deleteIfExists(file);
                            counter.inc();
                        } catch (IOException e) {
                            throw new IOException("Failed to delete file: " + file + " - " + e.getMessage(), e);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc != null) throw exc;
                        try {
                            Files.deleteIfExists(dir);
                            counter.inc();
                        } catch (IOException e) {
                            throw new IOException("Failed to delete directory: " + dir + " - " + e.getMessage(), e);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                return "Removed directory tree: " + resolved + " (" + counter.count + " entries deleted)";
            } else {
                // Regular file or symlink: delete the path itself
                Files.delete(resolved);
                return "Removed: " + resolved.toAbsolutePath();
            }
        } catch (Exception e) {
            return "Failed to remove path: " + e.getMessage();
        }
    }

    @Tool(description = "Rename or move a file/directory within the current working directory")
    public String renamePath(String oldName, String newName) {
        try {
            if (oldName == null || oldName.isBlank() || newName == null || newName.isBlank()) {
                return "Both source and destination names must be provided.";
            }

            // Expand environment vars / ~
            String expandedOld = expandPath(oldName);
            String expandedNew = expandPath(newName);

            Path base = Paths.get(currentDirectory).toAbsolutePath().normalize();
            Path oldPath = Paths.get(expandedOld);
            Path newPath = Paths.get(expandedNew);

            // Resolve relative paths
            Path resolvedOld = oldPath.isAbsolute() ? oldPath.normalize() : base.resolve(oldPath).normalize();
            Path resolvedNew = newPath.isAbsolute() ? newPath.normalize() : base.resolve(newPath).normalize();

            // Safety: only allow inside currentDirectory
            if (!resolvedOld.startsWith(base) || !resolvedNew.startsWith(base)) {
                return "Refusing to rename outside current working directory.";
            }

            if (!Files.exists(resolvedOld)) {
                return "Source does not exist: " + resolvedOld.toAbsolutePath();
            }

            if (Files.exists(resolvedNew)) {
                return "Destination already exists: " + resolvedNew.toAbsolutePath();
            }

            // Ensure parent dirs exist
            Files.createDirectories(resolvedNew.getParent());

            Files.move(resolvedOld, resolvedNew);
            return "Renamed " + resolvedOld.toAbsolutePath() + " → " + resolvedNew.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to rename: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new directory. Absolute paths are honored; relative paths are resolved against the current working directory (mkdir -p).")
    public String makeDirectory(String dirName) {
        try {
            if (dirName == null || dirName.isBlank()) {
                return "Directory name must be provided.";
            }

            // Expand ~ and env vars (e.g., ~/kog_tmp, $HOME/kog_tmp)
            String expanded = expandPath(dirName);

            Path base = Paths.get(currentDirectory).toAbsolutePath().normalize();
            Path candidate = Paths.get(expanded);

            // If absolute (e.g., /Users/you/kog_tmp), use as-is; else resolve under currentDirectory
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : base.resolve(candidate).normalize();

            if (Files.exists(resolved)) {
                if (Files.isDirectory(resolved)) {
                    return "Directory already exists: " + resolved.toAbsolutePath();
                } else {
                    return "A file with the same name already exists: " + resolved.toAbsolutePath();
                }
            }

            Files.createDirectories(resolved);

            // Match your desired UX message
            return "The directory `" + resolved.getFileName() + "` has been created successfully.";
        } catch (Exception e) {
            return "Failed to create directory: " + e.getMessage();
        }
    }


    @Tool(description = "Create a new empty file under the current working directory")
    public String createFile(String fileName) {
        try {
            if (fileName == null || fileName.isBlank()) {
                return "File name must be provided.";
            }

            // Expand ~ and environment variables
            String expanded = expandPath(fileName);

            Path base = Paths.get(currentDirectory).toAbsolutePath().normalize();
            Path candidate = Paths.get(expanded);

            // Resolve relative path against currentDirectory
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : base.resolve(candidate).normalize();

            // Safety: only allow creating inside currentDirectory
            if (!resolved.startsWith(base)) {
                return "Refusing to create file outside current working directory: " + resolved;
            }

            if (Files.exists(resolved)) {
                return "File already exists: " + resolved.toAbsolutePath();
            }

            // Make sure parent directories exist
            Files.createDirectories(resolved.getParent());

            // Create the new file
            Files.createFile(resolved);

            return "Created file: " + resolved.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to create file: " + e.getMessage();
        }
    }

    @Tool(description = "Write text content into a file under the current working directory (overwrites existing content)")
    public String writeFile(String fileName, String content) {
        try {
            if (fileName == null || fileName.isBlank()) {
                return "File name must be provided.";
            }

            // Expand ~ and environment variables
            String expanded = expandPath(fileName);

            Path base = Paths.get(currentDirectory).toAbsolutePath().normalize();
            Path candidate = Paths.get(expanded);

            // Resolve relative path against currentDirectory
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : base.resolve(candidate).normalize();

            // Safety: only allow inside currentDirectory
            if (!resolved.startsWith(base)) {
                return "Refusing to write file outside current working directory: " + resolved;
            }

            // Ensure parent directories exist
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }

            // Write content (overwrites existing file)
            Files.writeString(resolved, content == null ? "" : content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            return "Wrote file: " + resolved.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to write file: " + e.getMessage();
        }
    }



    @Tool(description = "Move or rename a file/directory. Absolute paths are honored; relatives resolve against current working directory. Set overwrite=true to replace existing target.")
    public String movePath(String source, String target, boolean overwrite) {
        try {
            if (source == null || source.isBlank() || target == null || target.isBlank()) {
                return "Source and target must be provided.";
            }

            // Expand ~ and $VARS
            String srcExpanded = expandPath(source);
            String dstExpanded = expandPath(target);

            Path base = Paths.get(currentDirectory).toAbsolutePath().normalize();

            Path src = Paths.get(srcExpanded);
            Path dst = Paths.get(dstExpanded);

            // Use absolute as-is; otherwise resolve under currentDirectory
            src = src.isAbsolute() ? src.normalize() : base.resolve(src).normalize();
            dst = dst.isAbsolute() ? dst.normalize() : base.resolve(dst).normalize();

            if (!Files.exists(src)) {
                return "Source does not exist: " + src.toAbsolutePath();
            }
            if (Files.exists(dst) && !overwrite) {
                return "Target already exists: " + dst.toAbsolutePath();
            }

            // Ensure parent dir exists
            if (dst.getParent() != null) {
                Files.createDirectories(dst.getParent());
            }

            // Try a direct move first
            try {
                if (overwrite) {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(src, dst);
                }
            } catch (IOException moveErr) {
                // Fallback for cross-device moves: copy then delete
                copyRecursive(src, dst, overwrite);
                deleteRecursive(src);
            }

            return "Moved: " + src.toAbsolutePath() + " → " + dst.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to move: " + e.getMessage();
        }
    }

    /** Copy file or directory recursively. Overwrites target if requested. */
    private static void copyRecursive(Path src, Path dst, boolean overwrite) throws IOException {
        if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
            // Copy directory tree
            try (Stream<Path> paths = Files.walk(src)) {
                for (Path p : (Iterable<Path>) paths::iterator) {
                    Path rel = src.relativize(p);
                    Path targetPath = dst.resolve(rel);
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        if (overwrite) {
                            Files.copy(p, targetPath, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                        } else {
                            Files.copy(p, targetPath, LinkOption.NOFOLLOW_LINKS);
                        }
                    }
                }
            }
        } else {
            // Copy single file or symlink target as a file copy
            Files.createDirectories(dst.getParent());
            if (overwrite) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            } else {
                Files.copy(src, dst, LinkOption.NOFOLLOW_LINKS);
            }
        }
    }

    /** Delete file or directory recursively (does not follow directory symlinks). */
    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            Files.deleteIfExists(path);
        }
    }




    @Tool(description = "Append text content to a file under the current working directory (creates file if missing)")
    public String appendFile(String fileName, String content) {
        try {
            if (fileName == null || fileName.isBlank()) {
                return "File name must be provided.";
            }

            // Expand ~ and environment variables
            String expanded = expandPath(fileName);

            Path base = Paths.get(currentDirectory).toAbsolutePath().normalize();
            Path candidate = Paths.get(expanded);

            // Resolve relative path against currentDirectory
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : base.resolve(candidate).normalize();

            // Safety: only allow inside currentDirectory
            if (!resolved.startsWith(base)) {
                return "Refusing to append to file outside current working directory: " + resolved;
            }

            // Ensure parent directories exist
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }

            // Append content (create if missing)
            Files.writeString(
                    resolved,
                    (content == null ? "" : content) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

            return "Appended to file: " + resolved.toAbsolutePath();
        } catch (Exception e) {
            return "Failed to append to file: " + e.getMessage();
        }
    }

    private static final class DeletionCounter {
        int count = 0;
        void inc() { count++; }
    }
}