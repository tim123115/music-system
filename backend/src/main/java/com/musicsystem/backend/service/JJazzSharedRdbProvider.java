package com.musicsystem.backend.service;

import org.jjazz.rhythmdatabase.api.DefaultRhythmDatabaseImpl;
import org.jjazz.rhythmdatabase.api.RhythmDatabase;
import org.jjazz.rhythmdatabase.spi.SharedRdbInstanceProvider;
import org.openide.util.NbPreferences;
import org.openide.util.lookup.ServiceProvider;

import java.util.concurrent.Future;

/**
 * Registers a shared RhythmDatabase instance for JJazz toolkit usage in this backend.
 */
@ServiceProvider(service = SharedRdbInstanceProvider.class)
public class JJazzSharedRdbProvider implements SharedRdbInstanceProvider {
    private DefaultRhythmDatabaseImpl rdb;

    @Override
    public synchronized Future<?> initialize() {
        if (rdb == null) {
            rdb = new DefaultRhythmDatabaseImpl(NbPreferences.forModule(getClass()));
            rdb.addRhythmsFromRhythmProviders(false, false, false);
        }
        return null;
    }

    @Override
    public synchronized boolean isInitialized() {
        return rdb != null;
    }

    @Override
    public synchronized RhythmDatabase get() {
        if (!isInitialized()) {
            initialize();
        }
        return rdb;
    }

    @Override
    public void markForStartupRefresh(boolean b) {
        // No-op for backend runtime.
    }

    @Override
    public boolean isMarkedForStartupRefresh() {
        return false;
    }
}
