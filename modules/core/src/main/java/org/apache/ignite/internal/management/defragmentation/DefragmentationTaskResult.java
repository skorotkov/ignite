/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.management.defragmentation;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import org.apache.ignite.internal.dto.IgniteDataTransferObject;
import org.apache.ignite.internal.util.typedef.internal.U;

/** */
public class DefragmentationTaskResult extends IgniteDataTransferObject {
    /** Serial version uid. */
    private static final long serialVersionUID = 0L;

    /** */
    private boolean success;

    /** */
    private String msg;

    /** Empty constructor for serialization. */
    public DefragmentationTaskResult() {
        // No-op.
    }

    /** */
    public DefragmentationTaskResult(boolean success, String msg) {
        this.success = success;

        this.msg = msg;
    }

    /** */
    public boolean isSuccess() {
        return success;
    }

    /** */
    public String getMessage() {
        return msg;
    }

    /** {@inheritDoc} */
    @Override protected void writeExternalData(ObjectOutput out) throws IOException {
        out.writeBoolean(success);

        U.writeString(out, msg);
    }

    /** {@inheritDoc} */
    @Override protected void readExternalData(ObjectInput in) throws IOException {
        success = in.readBoolean();

        msg = U.readString(in);
    }
}
