package bt.protocol;

import bt.BtException;
import bt.metainfo.TorrentId;
import bt.net.PeerId;
import bt.protocol.handler.BitfieldHandler;
import bt.protocol.handler.CancelHandler;
import bt.protocol.handler.ChokeHandler;
import bt.protocol.handler.HaveHandler;
import bt.protocol.handler.InterestedHandler;
import bt.protocol.handler.MessageHandler;
import bt.protocol.handler.NotInterestedHandler;
import bt.protocol.handler.PieceHandler;
import bt.protocol.handler.RequestHandler;
import bt.protocol.handler.UnchokeHandler;
import com.google.inject.Inject;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static bt.protocol.Protocols.readInt;

public class StandardBittorrentProtocol implements Protocol<Message>, MessageHandler<Message> {

    private static final String PROTOCOL_NAME = "BitTorrent protocol";

    public static final int CHOKE_ID = 0;
    public static final int UNCHOKE_ID = 1;
    public static final int INTERESTED_ID = 2;
    public static final int NOT_INTERESTED_ID = 3;
    public static final int HAVE_ID = 4;
    public static final int BITFIELD_ID = 5;
    public static final int REQUEST_ID = 6;
    public static final int PIECE_ID = 7;
    public static final int CANCEL_ID = 8;

    private static final byte[] PROTOCOL_NAME_BYTES;
    private static final byte[] HANDSHAKE_PREFIX;
    private static final int HANDSHAKE_RESERVED_OFFSET;
    private static final int HANDSHAKE_RESERVED_LENGTH = 8;

    private static final byte[] KEEPALIVE = new byte[]{0,0,0,0};

    static {

        PROTOCOL_NAME_BYTES = PROTOCOL_NAME.getBytes(Charset.forName("ASCII"));
        int protocolNameLength = PROTOCOL_NAME_BYTES.length;
        int prefixLength = 1;

        HANDSHAKE_RESERVED_OFFSET = 1 + protocolNameLength;
        HANDSHAKE_PREFIX = new byte[HANDSHAKE_RESERVED_OFFSET];
        HANDSHAKE_PREFIX[0] = (byte) protocolNameLength;
        System.arraycopy(PROTOCOL_NAME_BYTES, 0, HANDSHAKE_PREFIX, prefixLength, protocolNameLength);
    }

    private Map<Integer, MessageHandler<?>> handlers;
    private Map<Integer, Class<? extends Message>> uniqueTypes;
    private Map<Class<? extends Message>, MessageHandler<?>> handlersByType;
    private Map<Class<? extends Message>, Integer> idMap;

    @Inject
    public StandardBittorrentProtocol(Map<Integer, MessageHandler<?>> extraHandlers) {

        Map<Integer, MessageHandler<?>> handlers = new HashMap<>();
        handlers.put(CHOKE_ID, new ChokeHandler());
        handlers.put(UNCHOKE_ID, new UnchokeHandler());
        handlers.put(INTERESTED_ID, new InterestedHandler());
        handlers.put(NOT_INTERESTED_ID, new NotInterestedHandler());
        handlers.put(HAVE_ID, new HaveHandler());
        handlers.put(BITFIELD_ID, new BitfieldHandler());
        handlers.put(REQUEST_ID, new RequestHandler());
        handlers.put(PIECE_ID, new PieceHandler());
        handlers.put(CANCEL_ID, new CancelHandler());

        extraHandlers.forEach((messageId, handler) -> {
            if (handlers.containsKey(messageId)) {
                throw new BtException("Duplicate handler for message ID: " + messageId);
            }
            handlers.put(messageId, handler);
        });

        Map<Class<? extends Message>, Integer> idMap = new HashMap<>();
        Map<Class<? extends Message>, MessageHandler<?>> handlersByType = new HashMap<>();
        Map<Integer, Class<? extends Message>> uniqueTypes = new HashMap<>();

        handlers.forEach((messageId, handler) -> {

            if (handler.getSupportedTypes().isEmpty()) {
                throw new BtException("No supported types declared in handler: " + handler.getClass().getName());
            } else {
                uniqueTypes.put(messageId, handler.getSupportedTypes().iterator().next());
            }

            handler.getSupportedTypes().forEach(messageType -> {
                    if (idMap.containsKey(messageType)) {
                        throw new BtException("Duplicate handler for message type: " + messageType.getSimpleName());
                    }
                    idMap.put(messageType, messageId);
                    handlersByType.put(messageType, handler);
                }
            );
        });

        this.handlers = handlers;
        this.idMap = idMap;
        this.handlersByType = handlersByType;
        this.uniqueTypes = uniqueTypes;
    }

    @Override
    public Collection<Class<? extends Message>> getSupportedTypes() {
        return null;
    }

    @Override
    public final Class<? extends Message> readMessageType(ByteBuffer buffer) {

        Objects.requireNonNull(buffer);

        if (!buffer.hasRemaining()) {
            return null;
        }

        buffer.mark();
        byte first = buffer.get();
        if (first == PROTOCOL_NAME.length()) {
            return Handshake.class;
        }
        buffer.reset();

        Integer length = readInt(buffer);
        if (length == null) {
            return null;
        } else if (length == 0) {
            return KeepAlive.class;
        }

        if (buffer.hasRemaining()) {
            int messageTypeId = buffer.get();
            Class<? extends Message> messageType;

            messageType = uniqueTypes.get(messageTypeId);
            if (messageType == null) {
                MessageHandler<?> handler = handlers.get(messageTypeId);
                if (handler == null) {
                    throw new InvalidMessageException("Unknown message type ID: " + messageTypeId);
                }
                messageType = handler.readMessageType(buffer);
            }
            return messageType;
        }

        return null;
    }

    @Override
    public final int decode(MessageContext context, ByteBuffer buffer) {

        Objects.requireNonNull(context);
        Objects.requireNonNull(buffer);

        if (!buffer.hasRemaining()) {
            return 0;
        }

        buffer.mark();
        Class<? extends Message> messageType = readMessageType(buffer);
        if (messageType == null) {
            return 0;
        }

        if (Handshake.class.equals(messageType)) {
            buffer.reset();
            return decodeHandshake(context, buffer);
        }
        if (KeepAlive.class.equals(messageType)) {
            context.setMessage(KeepAlive.instance());
            return KEEPALIVE.length;
        }

        MessageHandler<?> handler = Objects.requireNonNull(handlersByType.get(messageType));
        buffer.reset();
        return handler.decode(context, buffer);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final boolean encode(Message message, ByteBuffer buffer) {

        Objects.requireNonNull(buffer);
        Integer messageId = idMap.get(Objects.requireNonNull(message).getClass());

        if (Handshake.class.equals(message.getClass())) {
            Handshake handshake = (Handshake) message;
            return writeHandshake(buffer, handshake.getReserved(), handshake.getTorrentId(), handshake.getPeerId());
        }
        if (KeepAlive.class.equals(message.getClass())) {
            return writeKeepAlive(buffer);
        }

        if (messageId == null) {
            throw new InvalidMessageException("Unknown message type: " + message.getClass().getSimpleName());
        }
        return ((MessageHandler<Message>)handlers.get(messageId)).encode(message, buffer);
    }

    // keep-alive: <len=0000>
    private static boolean writeKeepAlive(ByteBuffer buffer) {
        if (buffer.remaining() < KEEPALIVE.length) {
            return false;
        }
        buffer.put(KEEPALIVE);
        return true;
    }

    // handshake: <pstrlen><pstr><reserved><info_hash><peer_id>
    private static boolean writeHandshake(ByteBuffer buffer, byte[] reserved, TorrentId torrentId, PeerId peerId) {

        if (reserved.length != HANDSHAKE_RESERVED_LENGTH) {
            throw new InvalidMessageException("Invalid reserved bytes: expected " + HANDSHAKE_RESERVED_LENGTH
                    + " bytes, received " + reserved.length);
        }

        int length = HANDSHAKE_PREFIX.length + HANDSHAKE_RESERVED_LENGTH +
                TorrentId.length() + PeerId.length();
        if (buffer.remaining() < length) {
            return false;
        }

        buffer.put(HANDSHAKE_PREFIX);
        buffer.put(reserved);
        buffer.put(torrentId.getBytes());
        buffer.put(peerId.getBytes());

        return true;
    }

    private static int decodeHandshake(MessageContext context, ByteBuffer buffer) {

        int consumed = 0;
        int offset = HANDSHAKE_RESERVED_OFFSET;
        int length = HANDSHAKE_RESERVED_LENGTH + TorrentId.length() + PeerId.length();
        int limit = offset + length;

        if (buffer.remaining() >= limit) {

            buffer.get(); // skip message ID

            byte[] protocolNameBytes = new byte[PROTOCOL_NAME.length()];
            buffer.get(protocolNameBytes);
            if (!Arrays.equals(PROTOCOL_NAME_BYTES, protocolNameBytes)) {
                throw new InvalidMessageException("Unexpected protocol name (decoded with ASCII): " +
                        new String(protocolNameBytes, Charset.forName("ASCII")));
            }

            byte[] reserved = new byte[HANDSHAKE_RESERVED_LENGTH];
            buffer.get(reserved);

            byte[] infoHash = new byte[TorrentId.length()];
            buffer.get(infoHash);

            byte[] peerId = new byte[PeerId.length()];
            buffer.get(peerId);

            context.setMessage(new Handshake(reserved, TorrentId.fromBytes(infoHash), PeerId.fromBytes(peerId)));
            consumed = limit;
        }

        return consumed;
    }
}
