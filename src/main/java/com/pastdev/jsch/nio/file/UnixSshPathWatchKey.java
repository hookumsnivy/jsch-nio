package com.pastdev.jsch.nio.file;


import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class UnixSshPathWatchKey implements WatchKey, Runnable {
    private static Logger logger = LoggerFactory.getLogger( UnixSshPathWatchKey.class );
    private Map<UnixSshPath, UnixSshPathWatchEvent<Path>> addMap;
    private boolean cancelled;
    private Map<UnixSshPath, UnixSshPathWatchEvent<Path>> deleteMap;
    private UnixSshPath dir;
    private Map<UnixSshPath, PosixFileAttributes> entries;
    private ReentrantLock mapLock;
    private Map<UnixSshPath, UnixSshPathWatchEvent<Path>> modifyMap;
    private long pollingTimeout;
    private State state;
    private ReentrantLock stateLock;
    private UnixSshFileSystemWatchService watchService;

    public UnixSshPathWatchKey( UnixSshFileSystemWatchService watchService, UnixSshPath dir, Kind<?>[] events, long pollingTimeout ) {
        this.watchService = watchService;
        this.dir = dir;
        this.pollingTimeout = pollingTimeout;
        this.cancelled = false;
        this.addMap = new HashMap<>();
        this.deleteMap = new HashMap<>();
        this.modifyMap = new HashMap<>();
        this.state = State.READY;
    }

    void addCreateEvent( UnixSshPath path ) {
        try {
            mapLock.lock();
            logger.trace( "added: {}", path );
            if ( !addMap.containsKey( path ) ) {
                addMap.put( path, new UnixSshPathWatchEvent<Path>( StandardWatchEventKinds.ENTRY_CREATE, path ) );
            }
        }
        finally {
            mapLock.unlock();
        }
        signal();
    }

    void addDeleteEvent( UnixSshPath path ) {
        try {
            mapLock.lock();
            logger.trace( "deleted: {}", path );
            if ( !deleteMap.containsKey( path ) ) {
                deleteMap.put( path, new UnixSshPathWatchEvent<Path>( StandardWatchEventKinds.ENTRY_DELETE, path ) );
            }
        }
        finally {
            mapLock.unlock();
        }
        signal();
    }

    void addModifyEvent( UnixSshPath path ) {
        try {
            mapLock.lock();
            logger.trace( "modified: {}", path );
            if ( modifyMap.containsKey( path ) ) {
                modifyMap.get( path ).increment();
            }
            else {
                UnixSshPathWatchEvent<Path> event = new UnixSshPathWatchEvent<Path>( StandardWatchEventKinds.ENTRY_MODIFY, path );
                modifyMap.put( path, event );
            }
        }
        finally {
            mapLock.unlock();
        }
        signal();
    }

    @Override
    public void cancel() {
        watchService.unregister( this );
        cancelled = true;
    }

    @Override
    public boolean isValid() {
        return (!cancelled) && (!watchService.closed());
    }

    private static boolean modified( PosixFileAttributes attributes, PosixFileAttributes otherAttributes ) {
        if ( attributes.size() != otherAttributes.size() ) {
            return true;
        }
        if ( attributes.lastModifiedTime() != otherAttributes.lastModifiedTime() ) {
            return true;
        }
        return false;
    }

    @Override
    public List<WatchEvent<?>> pollEvents() {
        try {
            mapLock.lock();

            List<WatchEvent<?>> currentEvents = new ArrayList<>();
            currentEvents.addAll( addMap.values() );
            currentEvents.addAll( deleteMap.values() );
            currentEvents.addAll( modifyMap.values() );

            addMap.clear();
            deleteMap.clear();
            modifyMap.clear();

            return Collections.unmodifiableList( currentEvents );
        }
        finally {
            mapLock.unlock();
        }
    }

    @Override
    public boolean reset() {
        if ( !isValid() ) {
            return false;
        }

        try {
            mapLock.lock();
            if ( addMap.size() > 0 || deleteMap.size() > 0 || modifyMap.size() > 0 ) {
                signal();
                return true;
            }
        }
        finally {
            mapLock.unlock();
        }

        try {
            stateLock.lock();
            state = State.READY;
        }
        finally {
            stateLock.unlock();
        }

        return true;
    }

    @Override
    public void run() {
        try {
            while ( true ) {
                if ( ! isValid() ) {
                    break;
                }
                try {
                    logger.trace( "polling {}", dir );
                    Map<UnixSshPath, PosixFileAttributes> entries =
                            dir.getFileSystem().provider().statDirectory( dir );
                    for ( UnixSshPath entryPath : entries.keySet() ) {
                        if ( this.entries.containsKey( entryPath ) ) {
                            if ( modified( entries.get( entryPath ), this.entries.remove( entryPath ) ) ) {
                                addModifyEvent( entryPath );
                            }
                        }
                        else {
                            addCreateEvent( entryPath );
                        }
                    }
                    for ( UnixSshPath entryPath : this.entries.keySet() ) {
                        addDeleteEvent( entryPath );
                    }
                    this.entries = entries;
                }
                catch ( IOException e ) {
                    logger.error( "checking {} failed: {}", dir, e );
                    logger.debug( "checking directory failed: ", e );
                }
                Thread.sleep( pollingTimeout );
            }
        }
        catch ( InterruptedException e ) {
            // time to close out
            logger.debug( "interrupt caught, closing down poller" );
        }
        logger.info( "poller stopped for {}", dir );
    }

    private void signal() {
        try {
            stateLock.lock();
            if ( state != State.SIGNALLED ) {
                state = State.SIGNALLED;
                watchService.enqueue( this );
            }
        }
        finally {
            stateLock.unlock();
        }
    }

    @Override
    public UnixSshPath watchable() {
        return dir;
    }

    private enum State {
        READY, SIGNALLED
    }
}
