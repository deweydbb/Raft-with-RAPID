/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  The ASF licenses
 * this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.data.technology.jraft;

public enum RaftMessageType {

    RequestVoteRequest {
        @Override
        public String toString() {
            return "RequestVoteRequest";
        }

        @Override
        public byte toByte() {
            return (byte) 1;
        }
    },
    RequestVoteResponse {
        @Override
        public String toString() {
            return "RequestVoteResponse";
        }

        @Override
        public byte toByte() {
            return (byte) 2;
        }
    },
    AppendEntriesRequest {
        @Override
        public String toString() {
            return "AppendEntriesRequest";
        }

        @Override
        public byte toByte() {
            return (byte) 3;
        }
    },
    AppendEntriesResponse {
        @Override
        public String toString() {
            return "AppendEntriesResponse";
        }

        @Override
        public byte toByte() {
            return (byte) 4;
        }
    },
    ClientRequest {
        @Override
        public String toString() {
            return "ClientRequest";
        }

        @Override
        public byte toByte() {
            return (byte) 5;
        }
    },
    GetClusterRequest {
        @Override
        public String toString() {
            return "GetClusterRequest";
        }

        @Override
        public byte toByte() {
            return (byte) 18;
        }
    },

    GetClusterResponse {
        @Override
        public String toString() {
            return "GetClusterResponse";
        }

        @Override
        public byte toByte() {
            return (byte) 19;
        }
    };

    public abstract byte toByte();

    public static RaftMessageType fromByte(byte value) {
        switch (value) {
            case 1:
                return RequestVoteRequest;
            case 2:
                return RequestVoteResponse;
            case 3:
                return AppendEntriesRequest;
            case 4:
                return AppendEntriesResponse;
            case 5:
                return ClientRequest;
            case 18:
                return GetClusterRequest;
            case 19:
                return GetClusterResponse;
        }

        throw new IllegalArgumentException("the value for the message type is not define: " + value);
    }
}
