using Xunit;

namespace ReSharperMcp.Tests
{
    public class RequestLogBufferTests
    {
        [Fact]
        public void SerializesSolutionNameIdAndPeerPort()
        {
            var entry = new RequestLogEntry
            {
                Solution = "Client",
                SolutionId = @"G:\_m88_work_space\idlexX55555\Client\Client.sln",
                PeerPort = 23742
            };

            var json = entry.ToJObject();

            Assert.Equal("Client", (string)json["solution"]);
            Assert.Equal(entry.SolutionId, (string)json["solutionId"]);
            Assert.Equal(23742, (int)json["peerPort"]);
        }
    }
}
