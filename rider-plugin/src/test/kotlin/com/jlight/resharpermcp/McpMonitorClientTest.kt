package com.jlight.resharpermcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpMonitorClientTest {
    @Test
    fun parsesSameNameSolutionsByStableId() {
        val snapshot = McpMonitorClient().parseSnapshot(
            """
            {
              "result": {
                "role": "primary",
                "port": 23741,
                "solutions": [
                  {"id":"D:/_workSpace/m88/idlexX/Client/Client.sln","name":"Client","path":"D:/_workSpace/m88/idlexX/Client/Client.sln","source":"local"},
                  {"id":"G:/_m88_work_space/idlexX55555/Client/Client.sln","name":"Client","path":"G:/_m88_work_space/idlexX55555/Client/Client.sln","source":"peer","peerPort":23742}
                ],
                "localSolutions": [
                  {"id":"D:/_workSpace/m88/idlexX/Client/Client.sln","name":"Client","path":"D:/_workSpace/m88/idlexX/Client/Client.sln","source":"local"}
                ],
                "clientCount":0,"clients":[],"toolStats":[],"nextIndex":0,
                "counts":{"local":0,"forwarded":0,"other":0,"errors":0},"logs":[]
              }
            }
            """.trimIndent()
        )

        assertEquals(2, snapshot.state.solutions.size)
        assertEquals(1, snapshot.state.localSolutions.size)
        assertEquals(1, snapshot.state.peerSolutions.size)
        assertEquals(1, snapshot.state.peerProcessCount)
        assertTrue(snapshot.state.peerSolutions.single().id.contains("idlexX55555"))
        assertTrue(formatSolutionLabel(snapshot.state.peerSolutions.single(), snapshot.state.solutions).contains("idlexX55555"))
    }

    @Test
    fun fallsBackFromIdToPathAndNameForOlderBackends() {
        val snapshot = McpMonitorClient().parseSnapshot(
            """
            {
              "result": {
                "role": "primary", "port": 23741,
                "solutions":[{"name":"Client","path":"D:/repo/Client.sln"}],
                "localSolutions":[{"name":"Client"}],
                "clientCount":0,"clients":[],"toolStats":[],"nextIndex":0,
                "counts":{"local":0,"forwarded":0,"other":0,"errors":0},"logs":[]
              }
            }
            """.trimIndent()
        )

        assertEquals("D:/repo/Client.sln", snapshot.state.solutions.single().id)
        assertEquals("Client", snapshot.state.localSolutions.single().id)
    }

    @Test
    fun parsesSolutionIdInForwardedLogs() {
        val snapshot = McpMonitorClient().parseSnapshot(
            """
            {
              "result": {
                "role":"primary","port":23741,"solutions":[],"localSolutions":[],
                "clientCount":0,"clients":[],"toolStats":[],"nextIndex":1,
                "counts":{"local":0,"forwarded":1,"other":0,"errors":0},
                "logs":[{"index":1,"ts":1,"method":"tools/call","tool":"search_symbol","kind":"forwarded","viaPrimary":false,"durationMs":1,"solution":"Client","solutionId":"G:/repo/Client.sln","peerPort":23742,"args":"{}","result":"ok","argsPreview":"{}","resultPreview":"ok","isError":false,"errorText":null,"argsPreviewTruncated":false,"resultPreviewTruncated":false}]
              }
            }
            """.trimIndent()
        )

        assertEquals("G:/repo/Client.sln", snapshot.logs.single().solutionId)
        assertEquals(23742, snapshot.logs.single().peerPort)
    }
}
