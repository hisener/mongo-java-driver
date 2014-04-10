/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.protocol;

import org.mongodb.CommandResult;
import org.mongodb.Decoder;
import org.mongodb.Document;
import org.mongodb.Encoder;
import org.mongodb.MongoFuture;
import org.mongodb.MongoNamespace;
import org.mongodb.connection.ByteBufferOutputBuffer;
import org.mongodb.connection.Connection;
import org.mongodb.connection.ResponseBuffers;
import org.mongodb.connection.ServerAddress;
import org.mongodb.connection.ServerDescription;
import org.mongodb.diagnostics.Loggers;
import org.mongodb.diagnostics.logging.Logger;
import org.mongodb.operation.QueryFlag;
import org.mongodb.operation.SingleResultFuture;
import org.mongodb.operation.SingleResultFutureCallback;
import org.mongodb.protocol.message.CommandMessage;
import org.mongodb.protocol.message.ReplyMessage;

import java.util.EnumSet;

import static java.lang.String.format;
import static org.mongodb.protocol.ProtocolHelper.encodeMessageToBuffer;
import static org.mongodb.protocol.ProtocolHelper.getCommandFailureException;
import static org.mongodb.protocol.ProtocolHelper.getMessageSettings;

public class CommandProtocol implements Protocol<CommandResult> {

    public static final Logger LOGGER = Loggers.getLogger("protocol.command");

    private final MongoNamespace namespace;
    private final Document command;
    private final Decoder<Document> commandResultDecoder;
    private final Encoder<Document> commandEncoder;
    private final EnumSet<QueryFlag> queryFlags;

    public CommandProtocol(final String database, final Document command, final EnumSet<QueryFlag> queryFlags,
                           final Encoder<Document> commandEncoder, final Decoder<Document> commandResultDecoder) {
        this.queryFlags = queryFlags;
        this.namespace = new MongoNamespace(database, MongoNamespace.COMMAND_COLLECTION_NAME);
        this.command = command;
        this.commandResultDecoder = commandResultDecoder;
        this.commandEncoder = commandEncoder;
    }

    public CommandResult execute(final Connection connection, final ServerDescription serverDescription) {
        LOGGER.debug(format("Sending command {%s : %s} to database %s on connection [%s] to server %s",
                            command.keySet().iterator().next(), command.values().iterator().next(),
                            namespace.getDatabaseName(), connection.getId(), connection.getServerAddress()));
        CommandResult commandResult = receiveMessage(connection, sendMessage(connection, serverDescription).getId());
        LOGGER.debug("Command execution complete");
        return commandResult;
    }

    private CommandMessage sendMessage(final Connection connection, final ServerDescription serverDescription) {
        ByteBufferOutputBuffer buffer = new ByteBufferOutputBuffer(connection);
        try {
            CommandMessage message = new CommandMessage(namespace.getFullName(), command, queryFlags, commandEncoder,
                                                        getMessageSettings(serverDescription));
            message.encode(buffer);
            connection.sendMessage(buffer.getByteBuffers(), message.getId());
            return message;
        } finally {
            buffer.close();
        }
    }

    private CommandResult receiveMessage(final Connection connection, final int messageId) {
        ResponseBuffers responseBuffers = connection.receiveMessage(messageId);
        try {
            ReplyMessage<Document> replyMessage = new ReplyMessage<Document>(responseBuffers, commandResultDecoder, messageId);
            return createCommandResult(replyMessage, connection.getServerAddress());
        } finally {
            responseBuffers.close();
        }
    }

    public MongoFuture<CommandResult> executeAsync(final Connection connection, final ServerDescription serverDescription) {
        SingleResultFuture<CommandResult> retVal = new SingleResultFuture<CommandResult>();

        ByteBufferOutputBuffer buffer = new ByteBufferOutputBuffer(connection);
        CommandMessage message = new CommandMessage(namespace.getFullName(), command, queryFlags, commandEncoder,
                                                    getMessageSettings(serverDescription));
        encodeMessageToBuffer(message, buffer);

        CommandResultCallback receiveCallback = new CommandResultCallback(new SingleResultFutureCallback<CommandResult>(retVal),
                                                                          commandResultDecoder,
                                                                          message.getId(),
                                                                          connection.getServerAddress());
        connection.sendMessageAsync(buffer.getByteBuffers(),
                                    message.getId(),
                                    new SendMessageCallback<CommandResult>(connection, buffer, message.getId(), retVal, receiveCallback));
        return retVal;
    }

    private CommandResult createCommandResult(final ReplyMessage<Document> replyMessage, final ServerAddress serverAddress) {
        CommandResult commandResult = new CommandResult(serverAddress,
                                                        replyMessage.getDocuments().get(0),
                                                        replyMessage.getElapsedNanoseconds());
        if (!commandResult.isOk()) {
            throw getCommandFailureException(commandResult);
        }

        return commandResult;
    }
}
