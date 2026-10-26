package com.shoplab.helpdesk.blockhound;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import reactor.blockhound.BlockHound;

/** Installs BlockHound once for the whole test run, so every test, including the Spring ones, is guarded. */
public class BlockHoundSessionListener implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        BlockHound.install(new HelpdeskBlockHoundIntegration());
    }
}
