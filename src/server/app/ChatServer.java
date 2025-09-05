package server.app;

import server.shared.Branch;
import server.util.ChatLogger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ChatServer (Java 8 compatible)
 * - Broadcast request to branch employees (no auto-pair).
 * - First ACCEPT wins; others get REQUEST_TAKEN.
 * - Shift Manager can LIST_CONVS + JOIN.
 * - Missed requests + CALLBACK.
 * - Prevent duplicate login per username.
 \ * Protocol:
 *  HELLO <username> <role:SALESPERSON|CASHIER|SHIFT_MANAGER> <branch:HOLON|TEL_AVIV|RISHON>
 *  REQUEST_ANY_OTHER_BRANCH
 *  REQUEST_BRANCH <branch>
 *  REQUEST_USER <username>
 *  ACCEPT <requestId>
 *  LIST_CONVS
 *  JOIN <conversationId>
 *  CALLBACK <username>
 *  MSG <text...>
 *  END
 *  QUIT
 */
public class ChatServer {

    // ========= session =========
    private static final class Session {
        final Socket sock;
        final BufferedReader in;
        final PrintWriter out;

        final String username;
        final String role;   // SALESPERSON/CASHIER/SHIFT_MANAGER
        final Branch branch;

        volatile boolean busy = false;
        volatile String conversationId = null;

        Session(Socket s, String username, String role, Branch branch) throws IOException {
            this.sock = s;
            this.in = new BufferedReader(new InputStreamReader(s.getInputStream()));
            this.out = new PrintWriter(new OutputStreamWriter(s.getOutputStream()), true);
            this.username = username;
            this.role = role;
            this.branch = branch;
        }

        void send(String line) { out.println(line); }
        void closeQuietly() { try { sock.close(); } catch (IOException ignored) {} }
        boolean isManager() { return "SHIFT_MANAGER".equalsIgnoreCase(role); }
    }

    // ========= conversation =========
    private static final class Conversation {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final Set<Session> members = Collections.newSetFromMap(new ConcurrentHashMap<Session, Boolean>());

        void add(Session s) { members.add(s); s.conversationId = id; s.busy = true; }
        void remove(Session s) { members.remove(s); s.conversationId = null; s.busy = false; }
        void broadcast(String line) {
            for (Session m : members) m.send(line);
        }
        String participantsCsv() {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (Session m : members) {
                if (!first) sb.append(",");
                sb.append(m.username);
                first = false;
            }
            return sb.toString();
        }
    }

    // ========= broadcast request =========
    private static final class BroadcastRequest {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final String requester;
        final Branch requesterBranch;
        final Branch targetBranch; // null => any other branch
        final Set<Session> notified = Collections.newSetFromMap(new ConcurrentHashMap<Session, Boolean>());
        final AtomicBoolean taken = new AtomicBoolean(false);
        volatile boolean cancelled = false;

        BroadcastRequest(String requester, Branch requesterBranch, Branch targetBranch) {
            this.requester = requester;
            this.requesterBranch = requesterBranch;
            this.targetBranch = targetBranch;
        }
    }

    // ========= state =========
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private final ConcurrentMap<String, Session> online = new ConcurrentHashMap<String, Session>();
    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<String, Conversation>();
    private final ConcurrentMap<Branch, Set<Session>> idleByBranch = new ConcurrentHashMap<Branch, Set<Session>>();
    private final ConcurrentMap<String, BroadcastRequest> openRequests = new ConcurrentHashMap<String, BroadcastRequest>();
    private final ConcurrentMap<String, List<String>> missedForUser = new ConcurrentHashMap<String, List<String>>();

    public ChatServer(int port) { this.port = port; }

    public void start() {
        System.out.println("ChatServer started on port " + port);
        ChatLogger.logServerStart(port);
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(port);
            while (true) {
                final Socket s = ss.accept();
                pool.submit(new Runnable() {
                    @Override public void run() { serve(s); }
                });
            }
        } catch (IOException e) {
            System.err.println("ChatServer fatal: " + e.getMessage());
            ChatLogger.logError("Server", "Fatal server error: " + e.getMessage(), e);
        } finally {
            if (ss != null) try { ss.close(); } catch (IOException ignored) {}
            ChatLogger.logServerStop();
        }
    }

    private void serve(Socket sock) {
        Session session = null;
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true);

            String hello = in.readLine();
            if (hello == null || !hello.startsWith("HELLO ")) { out.println("ERR HELLO_REQUIRED"); sock.close(); return; }
            String[] t = hello.trim().split(" ");
            if (t.length < 4) { out.println("ERR BAD_HELLO"); sock.close(); return; }

            String username = t[1];
            String role = t[2].toUpperCase();
            Branch branch;
            try {
                branch = Branch.valueOf(t[3].toUpperCase());
            } catch (Exception ex) { out.println("ERR BAD_BRANCH"); sock.close(); return; }

            Session newSession = new Session(sock, username, role, branch);
            Session old = online.putIfAbsent(username, newSession);
            if (old != null) { 
                ChatLogger.logWarning("Connection", "Duplicate login attempt from " + username);
                out.println("ERR DUPLICATE_LOGIN"); 
                sock.close(); 
                return; 
            }
            session = newSession;
            session.send("OK HELLO");
            ChatLogger.logUserConnected(username, role, branch);

            markIdle(session);
            deliverOpenRequestsToIdle(session);
            flushMissed(session);

            String line;
            while ((line = session.in.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                handle(session, line);
            }
        } catch (IOException ignored) {
        } finally {
            if (session != null) cleanupSession(session);
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    private void handle(Session s, String line) {
        try {
            String[] t = line.trim().split(" ", 2);
            String cmd = t[0].toUpperCase();
            String rest = (t.length > 1) ? t[1] : "";

            switch (cmd) {
                case "REQUEST_ANY_OTHER_BRANCH" -> requestAnyOtherBranch(s);
                case "REQUEST_BRANCH" -> requestBranch(s, rest);
                case "REQUEST_USER" -> requestUser(s, rest);
                case "ACCEPT" -> acceptRequest(s, rest);
                case "LIST_CONVS" -> listConvs(s);
                case "JOIN" -> joinConv(s, rest);
                case "CALLBACK" -> doCallback(s, rest);
                case "MSG" -> doMsg(s, rest);
                case "END" -> endConversation(s);
                case "QUIT" -> {
                    s.send("BYE");
                    closeAndCleanup(s);
                }
                default -> s.send("ERR UNKNOWN_CMD");
            }
        } catch (Exception e) {
            s.send("ERR " + e.getMessage().replace(' ', '_'));
            ChatLogger.logError("Session " + s.username, "Error handling command: " + e.getMessage(), e);
        }
    }

    // ===== requests =====
    private void requestAnyOtherBranch(Session s) {
        ensureNotBusy(s);
        BroadcastRequest br = new BroadcastRequest(s.username, s.branch, null);
        openRequests.put(br.id, br);

        for (Map.Entry<Branch, Set<Session>> e : idleByBranch.entrySet()) {
            Branch b = e.getKey();
            if (b == s.branch) continue;
            Set<Session> set = e.getValue();
            for (Session cand : set) {
                if (!cand.busy) notifyIncoming(br, cand);
            }
        }
        s.send("INFO REQUEST_BROADCASTED " + br.id);
        
        // Log request creation
        ChatLogger.logRequestCreated(br.id, s.username, s.branch, null);
    }

    private void requestBranch(Session s, String rest) {
        ensureNotBusy(s);
        if (rest == null || rest.trim().isEmpty()) { s.send("ERR BAD_ARGS"); return; }
        Branch target;
        try { target = Branch.valueOf(rest.trim().toUpperCase()); }
        catch (Exception e) { s.send("ERR BAD_BRANCH"); return; }
        if (target == s.branch) s.send("INFO TIP_SELECT_OTHER_BRANCH");

        BroadcastRequest br = new BroadcastRequest(s.username, s.branch, target);
        openRequests.put(br.id, br);

        Set<Session> set = idleByBranch.get(target);
        if (set != null) {
            for (Session cand : set) if (!cand.busy) notifyIncoming(br, cand);
        }
        s.send("INFO REQUEST_BROADCASTED " + br.id);
    }

    private void requestUser(Session s, String rest) {
        ensureNotBusy(s);
        String user = (rest == null ? "" : rest.trim());
        if (user.isEmpty()) { s.send("ERR BAD_ARGS"); return; }
        Session peer = online.get(user);
        if (peer != null && !peer.busy && !peer.username.equals(s.username)) {
            startConversation(s, peer);
        } else {
            // mark as missed for that user
            List<String> list = missedForUser.get(user);
            if (list == null) {
                list = new CopyOnWriteArrayList<String>();
                missedForUser.put(user, list);
            }
            if (!list.contains(s.username)) list.add(s.username);
            s.send("INFO USER_BUSY_OR_OFFLINE");
        }
    }

    private void acceptRequest(Session s, String rest) {
        ensureNotBusy(s);
        String id = (rest == null ? "" : rest.trim());
        if (id.isEmpty()) { s.send("ERR BAD_ARGS"); return; }
        BroadcastRequest br = openRequests.get(id);
        if (br == null || br.cancelled) { s.send("ERR NO_SUCH_REQUEST"); return; }
        if (br.taken.get()) { s.send("ERR REQUEST_ALREADY_TAKEN"); return; }

        boolean eligible = (br.targetBranch == null) ? (s.branch != br.requesterBranch) : (s.branch == br.targetBranch);
        if (!eligible) { s.send("ERR NOT_ELIGIBLE_FOR_THIS_REQUEST"); return; }

        Session requester = online.get(br.requester);
        if (requester == null || requester.busy) {
            s.send("ERR REQUESTER_NOT_AVAILABLE");
            cancelRequest(br);
            return;
        }

        if (!br.taken.compareAndSet(false, true)) { s.send("ERR REQUEST_ALREADY_TAKEN"); return; }
        startConversation(requester, s);
        notifyRequestTaken(br, s);
        openRequests.remove(br.id);
        
        // Log request acceptance
        ChatLogger.logRequestAccepted(br.id, br.requester, s.username, br.requesterBranch, s.branch);
    }

    private void listConvs(Session s) {
        if (!s.isManager()) { s.send("ERR NOT_ALLOWED"); return; }
        if (conversations.isEmpty()) { s.send("INFO NO_ACTIVE_CONVERSATIONS"); return; }
        for (Conversation c : conversations.values()) {
            s.send("CONV " + c.id + " " + c.participantsCsv());
        }
        s.send("OK END");
    }

    private void joinConv(Session s, String rest) {
        if (!s.isManager()) { s.send("ERR NOT_ALLOWED"); return; }
        String id = (rest == null ? "" : rest.trim());
        Conversation c = conversations.get(id);
        if (c == null) { s.send("ERR NO_SUCH_CONVERSATION"); return; }
        if (s.conversationId != null && !s.conversationId.equals(id)) {
            s.send("ERR ALREADY_IN_ANOTHER_CONVERSATION");
            return;
        }
        c.add(s);
        c.broadcast("MANAGER_JOINED " + s.username);
        s.send("PAIRED " + c.id + " " + c.participantsCsv());
        
        // Log manager joining conversation
        ChatLogger.logConversationJoined(c.id, s.username, c.participantsCsv());
    }

    private void doCallback(Session s, String rest) {
        ensureNotBusy(s);
        String target = (rest == null ? "" : rest.trim());
        if (target.isEmpty()) { s.send("ERR BAD_ARGS"); return; }
        Session peer = online.get(target);
        if (peer != null && !peer.busy && !peer.username.equals(s.username)) {
            startConversation(s, peer);
            List<String> list = missedForUser.get(s.username);
            if (list != null) list.remove(target);
        } else {
            s.send("INFO TARGET_NOT_AVAILABLE");
        }
    }

    private void doMsg(Session s, String text) {
        if (s.conversationId == null) { s.send("ERR NOT_IN_CONVERSATION"); return; }
        Conversation c = conversations.get(s.conversationId);
        if (c == null) { s.send("ERR CONVERSATION_ENDED"); return; }
        c.broadcast("MSG from " + s.username + " : " + text);
        
        // Log the message
        ChatLogger.logMessage(s.conversationId, s.username, text);
    }

    private void endConversation(Session s) {
        if (s.conversationId == null) { s.send("INFO NOT_IN_CONVERSATION"); return; }
        Conversation c = conversations.get(s.conversationId);
        if (c == null) {
            s.busy = false; s.conversationId = null; markIdle(s); deliverOpenRequestsToIdle(s); return;
        }

        c.remove(s);
        s.send("INFO LEFT_CONVERSATION");

        if (c.members.size() < 2) {
            // end for all
            List<Session> copy = new ArrayList<Session>(c.members);
            for (Session m : copy) {
                c.remove(m);
                m.send("INFO CONVERSATION_ENDED");
                markIdle(m);
                deliverOpenRequestsToIdle(m);
            }
            conversations.remove(c.id);
            
            // Log conversation end
            ChatLogger.logConversationEnded(c.id, c.participantsCsv());
        } else {
            c.broadcast("INFO " + s.username + "_LEFT");
            
            // Log user leaving conversation
            ChatLogger.logConversationLeft(c.id, s.username, c.participantsCsv());
        }

        markIdle(s);
        deliverOpenRequestsToIdle(s);
    }

    // ===== helpers =====
    private void startConversation(Session a, Session b) {
        ensureNotBusy(a); ensureNotBusy(b);
        unmarkIdle(a); unmarkIdle(b);

        Conversation c = new Conversation();
        c.add(a); c.add(b);
        conversations.put(c.id, c);

        String participants = c.participantsCsv();
        a.send("PAIRED " + c.id + " " + participants);
        b.send("PAIRED " + c.id + " " + participants);
        
        // Log conversation start
        ChatLogger.logConversationStarted(c.id, participants);
    }

    private void notifyIncoming(BroadcastRequest br, Session cand) {
        if (cand.username.equals(br.requester)) return;
        if (br.taken.get() || br.cancelled) return;
        if (br.targetBranch == null && cand.branch == br.requesterBranch) return;
        br.notified.add(cand);
        cand.send("INCOMING_REQUEST " + br.id + " " + br.requester + " " + br.requesterBranch);
    }

    private void notifyRequestTaken(BroadcastRequest br, Session acceptor) {
        for (Session s : br.notified) {
            if (s != acceptor) s.send("REQUEST_TAKEN " + br.id);
        }
        br.notified.clear();
    }

    private void cancelRequest(BroadcastRequest br) {
        br.cancelled = true;
        for (Session s : br.notified) s.send("REQUEST_CANCELLED " + br.id);
        br.notified.clear();
        openRequests.remove(br.id);
        
        // Log request cancellation
        ChatLogger.logRequestCancelled(br.id, br.requester, br.requesterBranch, "Request cancelled");
    }

    private void deliverOpenRequestsToIdle(Session s) {
        for (BroadcastRequest br : openRequests.values()) {
            if (br.taken.get() || br.cancelled) continue;
            boolean eligible = (br.targetBranch == null) ? (s.branch != br.requesterBranch) : (s.branch == br.targetBranch);
            if (eligible) notifyIncoming(br, s);
        }
    }

    private void markIdle(Session s) {
        Set<Session> set = idleByBranch.get(s.branch);
        if (set == null) {
            set = Collections.newSetFromMap(new ConcurrentHashMap<Session, Boolean>());
            idleByBranch.put(s.branch, set);
        }
        set.add(s);
    }

    private void unmarkIdle(Session s) {
        Set<Session> set = idleByBranch.get(s.branch);
        if (set != null) set.remove(s);
    }

    private void ensureNotBusy(Session s) {
        if (s.busy) throw new IllegalStateException("ALREADY_IN_CONVERSATION");
    }

    private void flushMissed(Session s) {
        List<String> list = missedForUser.remove(s.username);
        if (list != null) {
            for (String r : list) s.send("MISSED_REQUEST_FROM " + r);
        }
    }

    // ===== cleanup =====
    private void cleanupSession(Session s) {
        // Log user disconnection
        ChatLogger.logUserDisconnected(s.username, s.role, s.branch);
        
        // cancel open requests created by this user
        List<String> toCancel = new ArrayList<String>();
        for (Map.Entry<String, BroadcastRequest> e : openRequests.entrySet()) {
            BroadcastRequest br = e.getValue();
            if (br.requester.equals(s.username) && !br.taken.get()) toCancel.add(e.getKey());
        }
        for (String id : toCancel) {
            BroadcastRequest br = openRequests.get(id);
            if (br != null) cancelRequest(br);
        }

        // leave conversation
        if (s.conversationId != null) {
            Conversation c = conversations.get(s.conversationId);
            if (c != null) {
                c.remove(s);
                if (c.members.size() < 2) {
                    List<Session> copy = new ArrayList<Session>(c.members);
                    for (Session m : copy) {
                        c.remove(m);
                        m.send("INFO CONVERSATION_ENDED");
                        markIdle(m);
                        deliverOpenRequestsToIdle(m);
                    }
                    conversations.remove(c.id);
                } else {
                    c.broadcast("INFO " + s.username + "_LEFT");
                }
            }
        }
        unmarkIdle(s);
        online.remove(s.username, s);
        s.closeQuietly();
    }

    private void closeAndCleanup(Session s) {
        cleanupSession(s);
        try { s.sock.close(); } catch (IOException ignored) {}
    }

    public static void main(String[] args) {
        int p = 6060;
        if (args != null && args.length > 0) {
            try { p = Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        
        // Add shutdown hook for graceful logging
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ChatLogger.logServerStop();
        }));
        
        new ChatServer(p).start();
    }
}
