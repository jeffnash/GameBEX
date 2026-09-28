package com.gamebex.client.launch;

/**
 * File ops run by ShellFilesService inside the Shizuku (shell uid) process.
 * Every path must pass ShellPathPolicy (emulator Android/data dirs only);
 * anything else throws IllegalArgumentException/SecurityException.
 */
interface IShellFiles {
    /** Shizuku's reserved UserService teardown code. */
    void destroy() = 16777114;

    /** File text, or null when [path] is not a regular file. */
    String readText(String path) = 1;
    /** Temp file in the same dir, then rename (in-place fallback). */
    boolean writeTextAtomic(String path, String text) = 2;
    boolean exists(String path) = 3;
    boolean mkdirs(String path) = 4;
    /** Copies [path] to [dest]; false when [dest] already exists. */
    boolean copy(String path, String dest) = 5;
    boolean isFile(String path) = 6;
    boolean isDirectory(String path) = 7;
    /** `am force-stop [pkg]`; only known emulator packages (ForceStopPolicy). */
    boolean forceStop(String pkg) = 8;
    long lastModified(String path) = 9;
    /** Directory entry names, or null when [path] is not a directory. */
    List<String> listNames(String path) = 10;
}
