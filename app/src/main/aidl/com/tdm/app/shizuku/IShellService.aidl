package com.tdm.app.shizuku;

/**
 * Shizuku user service (runs as shell uid in Shizuku server process).
 * Only whitelisted commands are ever executed by TDM (spec §34).
 *
 * Result format: first line = exit code, remaining text = command output.
 */
interface IShellService {
    String runCommand(in String[] cmd);

    void destroy();
}
