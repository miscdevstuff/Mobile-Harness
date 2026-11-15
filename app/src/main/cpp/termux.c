#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))

static int throw_runtime_exception(JNIEnv *env, const char *message) {
    jclass ex_class = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (ex_class != NULL) {
        (*env)->ThrowNew(env, ex_class, message);
    }
    return -1;
}

static int create_subprocess(
    JNIEnv *env,
    const char *cmd,
    const char *cwd,
    char *const argv[],
    char *const envp[],
    int *pProcessId,
    jint rows,
    jint columns,
    jint cell_width,
    jint cell_height
) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) {
        return throw_runtime_exception(env, "Cannot open /dev/ptmx");
    }

    char devname[64];
    if (grantpt(ptm) != 0 || unlockpt(ptm) != 0 || ptsname_r(ptm, devname, sizeof(devname)) != 0) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    // Enable UTF-8 mode and disable XON/XOFF flow control so Ctrl+S does not freeze output.
    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize sz = {
        .ws_row = (unsigned short)(rows > 0 ? rows : 24),
        .ws_col = (unsigned short)(columns > 0 ? columns : 80),
        .ws_xpixel = (unsigned short)((columns > 0 ? columns : 80) * (cell_width > 0 ? cell_width : 0)),
        .ws_ypixel = (unsigned short)((rows > 0 ? rows : 24) * (cell_height > 0 ? cell_height : 0)),
    };
    ioctl(ptm, TIOCSWINSZ, &sz);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    }

    if (pid > 0) {
        *pProcessId = (int)pid;
        return ptm;
    }

    // Child process: restore default signal mask and detach from parent process group.
    sigset_t signals_to_unblock;
    sigfillset(&signals_to_unblock);
    sigprocmask(SIG_UNBLOCK, &signals_to_unblock, NULL);

    close(ptm);
    if (setsid() < 0) {
        _exit(126);
    }

    int pts = open(devname, O_RDWR);
    if (pts < 0) {
        _exit(126);
    }
    ioctl(pts, TIOCSCTTY, 0);

    dup2(pts, STDIN_FILENO);
    dup2(pts, STDOUT_FILENO);
    dup2(pts, STDERR_FILENO);
    if (pts > STDERR_FILENO) {
        close(pts);
    }

    // Close any inherited file descriptors above STDERR_FILENO.
    DIR *self_dir = opendir("/proc/self/fd");
    if (self_dir != NULL) {
        int dir_fd = dirfd(self_dir);
        struct dirent *entry;
        while ((entry = readdir(self_dir)) != NULL) {
            char *endptr = NULL;
            long fd = strtol(entry->d_name, &endptr, 10);
            if (endptr != entry->d_name && *endptr == '\0' && fd > STDERR_FILENO && fd != dir_fd) {
                close((int)fd);
            }
        }
        closedir(self_dir);
    }

    clearenv();
    if (envp != NULL) {
        for (char *const *e = envp; *e != NULL; ++e) {
            putenv((char *)*e);
        }
    }

    if (cwd != NULL && chdir(cwd) != 0) {
        char *error_message = NULL;
        if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1) error_message = "chdir()";
        perror(error_message);
        fflush(stderr);
    }

    // Required on Android so PRoot's ptrace tracer can attach to child processes.
    prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);

    execvp(cmd, argv);

    char *error_message = NULL;
    if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1) error_message = "exec()";
    perror(error_message);
    fflush(stderr);
    _exit(127);
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
    JNIEnv *env,
    jclass TERMUX_UNUSED(clazz),
    jstring cmd,
    jstring cwd,
    jobjectArray args,
    jobjectArray envVars,
    jintArray processIdArray,
    jint rows,
    jint columns,
    jint cell_width,
    jint cell_height
) {
    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char **argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) {
        return throw_runtime_exception(env, "Couldn't allocate argv array");
    }

    jsize envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char **envp = calloc((size_t)envc + 1, sizeof(char *));
    if (!envp) {
        free(argv);
        return throw_runtime_exception(env, "Couldn't allocate envp array");
    }

    int ptm = -1;
    const char *cmd_utf8 = NULL;
    const char *cwd_utf8 = NULL;

    for (jsize i = 0; i < argc; ++i) {
        jstring arg_str = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        if (!arg_str) {
            throw_runtime_exception(env, "Null argument string in args");
            goto cleanup;
        }
        const char *utf8 = (*env)->GetStringUTFChars(env, arg_str, NULL);
        if (!utf8) {
            (*env)->DeleteLocalRef(env, arg_str);
            throw_runtime_exception(env, "GetStringUTFChars failed for argument");
            goto cleanup;
        }
        argv[i] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, arg_str, utf8);
        (*env)->DeleteLocalRef(env, arg_str);
        if (!argv[i]) {
            throw_runtime_exception(env, "strdup failed for argument");
            goto cleanup;
        }
    }

    for (jsize i = 0; i < envc; ++i) {
        jstring env_str = (jstring)(*env)->GetObjectArrayElement(env, envVars, i);
        if (!env_str) {
            throw_runtime_exception(env, "Null environment string in envVars");
            goto cleanup;
        }
        const char *utf8 = (*env)->GetStringUTFChars(env, env_str, NULL);
        if (!utf8) {
            (*env)->DeleteLocalRef(env, env_str);
            throw_runtime_exception(env, "GetStringUTFChars failed for envVar");
            goto cleanup;
        }
        envp[i] = strdup(utf8);
        (*env)->ReleaseStringUTFChars(env, env_str, utf8);
        (*env)->DeleteLocalRef(env, env_str);
        if (!envp[i]) {
            throw_runtime_exception(env, "strdup failed for envVar");
            goto cleanup;
        }
    }

    cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (!cmd_utf8) {
        throw_runtime_exception(env, "GetStringUTFChars failed for cmd");
        goto cleanup;
    }

    if (cwd != NULL) {
        cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
        if (!cwd_utf8) {
            throw_runtime_exception(env, "GetStringUTFChars failed for cwd");
            goto cleanup;
        }
    }

    int proc_id = 0;
    ptm = create_subprocess(
        env,
        cmd_utf8,
        cwd_utf8,
        argv,
        envp,
        &proc_id,
        rows,
        columns,
        cell_width,
        cell_height
    );

    if (ptm >= 0) {
        int *p_proc_id = (int *)(*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
        if (!p_proc_id) {
            close(ptm);
            ptm = throw_runtime_exception(env, "GetPrimitiveArrayCritical(processIdArray) failed");
            goto cleanup;
        }
        *p_proc_id = proc_id;
        (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, p_proc_id, 0);
    }

cleanup:
    if (cwd_utf8 != NULL) (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);
    if (cmd_utf8 != NULL) (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    for (jsize i = 0; i < argc; ++i) free(argv[i]);
    free(argv);
    for (jsize i = 0; i < envc; ++i) free(envp[i]);
    free(envp);
    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
    JNIEnv *TERMUX_UNUSED(env),
    jclass TERMUX_UNUSED(clazz),
    jint fd,
    jint rows,
    jint cols,
    jint cell_width,
    jint cell_height
) {
    struct winsize sz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = (unsigned short)(cols * cell_width),
        .ws_ypixel = (unsigned short)(rows * cell_height),
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(
    JNIEnv *TERMUX_UNUSED(env),
    jclass TERMUX_UNUSED(clazz),
    jint fd
) {
    struct termios tios;
    if (tcgetattr(fd, &tios) == 0) {
        if ((tios.c_iflag & IUTF8) == 0) {
            tios.c_iflag |= IUTF8;
            tcsetattr(fd, TCSANOW, &tios);
        }
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(
    JNIEnv *TERMUX_UNUSED(env),
    jclass TERMUX_UNUSED(clazz),
    jint pid
) {
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    }
    return 0;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(
    JNIEnv *TERMUX_UNUSED(env),
    jclass TERMUX_UNUSED(clazz),
    jint fileDescriptor
) {
    close(fileDescriptor);
}
