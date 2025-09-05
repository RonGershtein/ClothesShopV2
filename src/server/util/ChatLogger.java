package server.util;

import server.shared.Branch;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Comprehensive chat logging system that tracks:
 * - User connections/disconnections
 * - Conversation lifecycle (start, join, leave, end)
 * - All chat messages with metadata
 * - Chat requests and responses
 * - System events and errors
 */
public class ChatLogger {
    private static final Logger logger = Loggers.chat();
    private static final Path CHAT_LOGS_DIR = Path.of("logs", "chat");
    private static final Path MESSAGES_FILE = CHAT_LOGS_DIR.resolve("messages.txt");
    private static final Path CONVERSATIONS_FILE = CHAT_LOGS_DIR.resolve("conversations.txt");
    private static final Path REQUESTS_FILE = CHAT_LOGS_DIR.resolve("requests.txt");
    private static final Path SESSIONS_FILE = CHAT_LOGS_DIR.resolve("sessions.txt");
    
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicLong messageCounter = new AtomicLong(0);
    private static final ConcurrentHashMap<String, Long> conversationStartTimes = new ConcurrentHashMap<>();
    
    static {
        try {
            Files.createDirectories(CHAT_LOGS_DIR);
            // Create header files if they don't exist
            createFileWithHeader(MESSAGES_FILE, "timestamp,message_id,conversation_id,sender,receiver,message_type,content");
            createFileWithHeader(CONVERSATIONS_FILE, "timestamp,conversation_id,event_type,participants,details");
            createFileWithHeader(REQUESTS_FILE, "timestamp,request_id,requester,requester_branch,target_branch,event_type,details");
            createFileWithHeader(SESSIONS_FILE, "timestamp,username,role,branch,event_type,details");
        } catch (IOException e) {
            logger.severe("Failed to initialize chat logging directories: " + e.getMessage());
        }
    }
    
    private static void createFileWithHeader(Path file, String header) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, header + "\n", StandardOpenOption.CREATE);
        }
    }
    
    // ========= Session Logging =========
    public static void logUserConnected(String username, String role, Branch branch) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logEntry = String.format("%s,%s,%s,%s,CONNECTED,User connected to chat server\n", 
                                      timestamp, username, role, branch);
        
        logToFile(SESSIONS_FILE, logEntry);
        logger.info("User connected: " + username + " (" + role + ") from " + branch);
    }
    
    public static void logUserDisconnected(String username, String role, Branch branch) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logEntry = String.format("%s,%s,%s,%s,DISCONNECTED,User disconnected from chat server\n", 
                                      timestamp, username, role, branch);
        
        logToFile(SESSIONS_FILE, logEntry);
        logger.info("User disconnected: " + username + " (" + role + ") from " + branch);
    }
    
    // ========= Conversation Logging =========
    public static void logConversationStarted(String conversationId, String participants) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        conversationStartTimes.put(conversationId, System.currentTimeMillis());
        
        String logEntry = String.format("%s,%s,STARTED,%s,Conversation started\n", 
                                      timestamp, conversationId, participants);
        
        logToFile(CONVERSATIONS_FILE, logEntry);
        logger.info("Conversation started: " + conversationId + " with participants: " + participants);
    }
    
    public static void logConversationJoined(String conversationId, String username, String participants) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logEntry = String.format("%s,%s,JOINED,%s,User %s joined\n", 
                                      timestamp, conversationId, participants, username);
        
        logToFile(CONVERSATIONS_FILE, logEntry);
        logger.info("User " + username + " joined conversation: " + conversationId);
    }
    
    public static void logConversationLeft(String conversationId, String username, String participants) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logEntry = String.format("%s,%s,LEFT,%s,User %s left\n", 
                                      timestamp, conversationId, participants, username);
        
        logToFile(CONVERSATIONS_FILE, logEntry);
        logger.info("User " + username + " left conversation: " + conversationId);
    }
    
    public static void logConversationEnded(String conversationId, String participants) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        Long startTime = conversationStartTimes.remove(conversationId);
        long duration = startTime != null ? (System.currentTimeMillis() - startTime) / 1000 : 0;
        
        String logEntry = String.format("%s,%s,ENDED,%s,Conversation ended (duration: %ds)\n", 
                                      timestamp, conversationId, participants, duration);
        
        logToFile(CONVERSATIONS_FILE, logEntry);
        logger.info("Conversation ended: " + conversationId + " (duration: " + duration + "s)");
    }
    
    // ========= Message Logging =========
    public static void logMessage(String conversationId, String sender, String content) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        long messageId = messageCounter.incrementAndGet();
        
        // Escape commas in content to maintain CSV format
        String escapedContent = content.replace(",", "\\,");
        
        String logEntry = String.format("%s,%d,%s,%s,ALL,CHAT,%s\n", 
                                      timestamp, messageId, conversationId, sender, escapedContent);
        
        logToFile(MESSAGES_FILE, logEntry);
        logger.fine("Message logged: " + messageId + " in " + conversationId + " from " + sender);
    }
    

    
    // ========= Request Logging =========
    public static void logRequestCreated(String requestId, String requester, Branch requesterBranch, Branch targetBranch) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String targetBranchStr = targetBranch != null ? targetBranch.toString() : "ANY_OTHER";
        
        String logEntry = String.format("%s,%s,%s,%s,%s,CREATED,Request created\n", 
                                      timestamp, requestId, requester, requesterBranch, targetBranchStr);
        
        logToFile(REQUESTS_FILE, logEntry);
        logger.info("Chat request created: " + requestId + " from " + requester + " (" + requesterBranch + ") to " + targetBranchStr);
    }
    
    public static void logRequestAccepted(String requestId, String requester, String acceptor, Branch requesterBranch, Branch acceptorBranch) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logEntry = String.format("%s,%s,%s,%s,%s,ACCEPTED,Accepted by %s (%s)\n", 
                                      timestamp, requestId, requester, requesterBranch, acceptorBranch, acceptor, acceptorBranch);
        
        logToFile(REQUESTS_FILE, logEntry);
        logger.info("Chat request accepted: " + requestId + " by " + acceptor + " (" + acceptorBranch + ")");
    }
    
    public static void logRequestCancelled(String requestId, String requester, Branch requesterBranch, String reason) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String logEntry = String.format("%s,%s,%s,%s,%s,CANCELLED,%s\n", 
                                      timestamp, requestId, requester, requesterBranch, "N/A", reason);
        
        logToFile(REQUESTS_FILE, logEntry);
        logger.info("Chat request cancelled: " + requestId + " from " + requester + " - " + reason);
    }
    
    // ========= Error and Warning Logging =========
    public static void logError(String context, String error, Exception exception) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        String details = exception != null ? exception.getMessage() : error;
        
        logger.severe("Chat error in " + context + ": " + details);
        if (exception != null) {
            logger.severe("Exception details: " + exception.toString());
        }
    }
    
    public static void logWarning(String context, String warning) {
        logger.warning("Chat warning in " + context + ": " + warning);
    }
    
    // ========= Utility Methods =========
    private static void logToFile(Path file, String logEntry) {
        try {
            Files.writeString(file, logEntry, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.severe("Failed to write to chat log file " + file.getFileName() + ": " + e.getMessage());
        }
    }
    
    public static void logServerStart(int port) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        logger.info("Chat server started on port " + port);
        
        String logEntry = String.format("%s,SYSTEM,SYSTEM,SYSTEM,SERVER_START,Server started on port %d\n", 
                                      timestamp, port);
        logToFile(SESSIONS_FILE, logEntry);
    }
    
    public static void logServerStop() {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
        logger.info("Chat server stopping");
        
        String logEntry = String.format("%s,SYSTEM,SYSTEM,SYSTEM,SERVER_STOP,Server stopping\n", timestamp);
        logToFile(SESSIONS_FILE, logEntry);
    }
}
