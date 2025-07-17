package be.iqit.tomcat.session;

import org.apache.catalina.*;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StoreBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import java.io.IOException;

public class SessionManager extends ManagerBase implements Lifecycle {

    private final Log log = LogFactory.getLog(SessionManager.class); // must not be static


    private static final String name = "SessionManager";

    protected Store store = null;

    protected boolean saveOnRestart = true;

    @Override
    public String getName() {
        return name;
    }

    public void setStore(Store store) {
        this.store = store;
        store.setManager(this);
    }

    public Store getStore() {
        return this.store;
    }

    @Override
    public void processExpires() {

        long timeNow = System.currentTimeMillis();
        Session[] sessions = findSessions();
        int expireHere = 0;
        if (log.isTraceEnabled()) {
            log.trace("Start expire sessions " + getName() + " at " + timeNow + " sessioncount " + sessions.length);
        }
        for (Session session : sessions) {
            if (!session.isValid()) {
                expiredSessions.incrementAndGet();
                expireHere++;
            }
        }
        if (getStore() instanceof StoreBase) {
            ((StoreBase) getStore()).processExpires();
        }

        long timeEnd = System.currentTimeMillis();
        if (log.isTraceEnabled()) {
            log.trace("End expire sessions " + getName() + " processingTime " + (timeEnd - timeNow) +
                    " expired sessions: " + expireHere);
        }
        processingTime += (timeEnd - timeNow);

    }

    @Override
    public Session findSession(String id) throws IOException {
        Session session = super.findSession(id);
        // OK, at this point, we're not sure if another thread is trying to
        // remove the session or not so the only way around this is to lock it
        // (or attempt to) and then try to get it by this session id again. If
        // the other code ran swapOut, then we should get a null back during
        // this run, and if not, we lock it out so we can access the session
        // safely.
        if (session != null) {
            synchronized (session) {
                session = super.findSession(session.getIdInternal());
                if (session != null) {
                    // To keep any external calling code from messing up the
                    // concurrency.
                    session.access();
                    session.endAccess();
                }
            }
        }
        if (session != null) {
            return session;
        }

        // See if the Session is in the Store
        try {
            session = getStore().load(id);
            if(session != null) {
                add(session);
            }
        } catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
        return session;
    }

    @Override
    public void load() {
    }

    @Override
    public void remove(Session session, boolean update) {
        super.remove(session, update);

        if (store != null) {
            removeSession(session.getIdInternal());
        }
    }

    protected void removeSession(String id) {
        try {
            store.remove(id);
        } catch (IOException e) {
            log.error(sm.getString("sessionManager.removeError"), e);
        }
    }

    @Override
    public void unload() {
    }

    @Override
    protected void startInternal() throws LifecycleException {

        super.startInternal();

        if (store == null) {
            log.error(sm.getString("sessionManager.noStore"));
        } else if (store instanceof Lifecycle) {
            ((Lifecycle) store).start();
        }

        setState(LifecycleState.STARTING);
    }

    @Override
    protected void stopInternal() throws LifecycleException {
        if (log.isTraceEnabled()) {
            log.trace("Stopping");
        }

        setState(LifecycleState.STOPPING);

        if (getStore() instanceof Lifecycle) {
            ((Lifecycle) getStore()).stop();
        }

        // Require a new random number generator if we are restarted
        super.stopInternal();
    }

}
