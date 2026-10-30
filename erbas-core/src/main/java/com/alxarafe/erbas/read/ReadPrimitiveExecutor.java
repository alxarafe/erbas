package com.alxarafe.erbas.read;

import com.alxarafe.erbas.auth.application.UserIdentity;
import tools.jackson.databind.JsonNode;

/** Explicit module binding: schema validation and business execution stay with its owner. */
public interface ReadPrimitiveExecutor {
    ReadPrimitive descriptor();
    Object validateInput(JsonNode input);
    Object execute(Context context, Object input);
    boolean validOutput(Object output, Object input);

    record Context(String invocationId, String primitiveId, String module, UserIdentity subject,
            ReadPrimitiveRouter.Origin origin) { }
    final class InvalidInput extends RuntimeException { }
}
