/*
 * Copyright 2015 - 2022 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 Gabor Somogyi (gabor.g.somogyi@gmail.com)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocol;
import org.traccar.ServerManager;
import org.traccar.api.ExtendedObjectResource;
import org.traccar.database.CommandsManager;
import org.traccar.model.Command;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.model.Typed;
import org.traccar.model.UserRestrictions;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Path("commands")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommandResource extends ExtendedObjectResource<Command> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommandResource.class);

    @Inject
    private CommandsManager commandsManager;

    @Inject
    private ServerManager serverManager;

    public CommandResource() {
        super(Command.class);
    }

    private BaseProtocol getDeviceProtocol(long deviceId) throws StorageException {
        Position position = storage.getObject(Position.class, new Request(
                new Columns.All(), new Condition.LatestPositions(deviceId)));
        if (position != null) {
            return serverManager.getProtocol(position.getProtocol());
        } else {
            return null;
        }
    }

    @GET
    @Path("send")
    public Collection<Command> get(@QueryParam("deviceId") long deviceId) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        BaseProtocol protocol = getDeviceProtocol(deviceId);
        return get(false, 0, 0, deviceId).stream().filter(command -> {
            String type = command.getType();
            if (protocol != null) {
                return command.getTextChannel() && protocol.getSupportedTextCommands().contains(type)
                        || !command.getTextChannel() && protocol.getSupportedDataCommands().contains(type);
            } else {
                return type.equals(Command.TYPE_CUSTOM);
            }
        }).collect(Collectors.toList());
    }

    @POST
    @Path("send")
    public Response send(Command entity) throws Exception {
        permissionsService.checkRestriction(getUserId(), UserRestrictions::getReadonly);
        if (entity.getId() > 0) {
            permissionsService.checkPermission(Command.class, getUserId(), entity.getId());
            long deviceId = entity.getDeviceId();
            entity = storage.getObject(baseClass, new Request(
                    new Columns.All(), new Condition.Equals("id", "id", entity.getId())));
            entity.setDeviceId(deviceId);
        } else {
            permissionsService.checkRestriction(getUserId(), UserRestrictions::getLimitCommands);
        }
        permissionsService.checkPermission(Device.class, getUserId(), entity.getDeviceId());
        if (!commandsManager.sendCommand(entity)) {
            return Response.accepted(entity).build();
        }
        return Response.ok(entity).build();
    }

    @GET
    @Path("types")
    public Collection<Typed> get(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("textChannel") boolean textChannel) throws StorageException {
        if (deviceId != 0) {
            permissionsService.checkPermission(Device.class, getUserId(), deviceId);
            BaseProtocol protocol = getDeviceProtocol(deviceId);
            if (protocol != null) {
                if (textChannel) {
                    return protocol.getSupportedTextCommands().stream().map(Typed::new).collect(Collectors.toList());
                } else {
                    return protocol.getSupportedDataCommands().stream().map(Typed::new).collect(Collectors.toList());
                }
            } else {
                return Collections.singletonList(new Typed(Command.TYPE_CUSTOM));
            }
        } else {
            List<Typed> result = new ArrayList<>();
            Field[] fields = Command.class.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("TYPE_")) {
                    try {
                        result.add(new Typed(field.get(null).toString()));
                    } catch (IllegalArgumentException | IllegalAccessException error) {
                        LOGGER.warn("Get command types error", error);
                    }
                }
            }
            return result;
        }
    }

}
