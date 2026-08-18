using System;
using Newtonsoft.Json.Linq;
using ReSharperMcp.Protocol;

namespace ReSharperMcp
{
    internal static class PeerRequestBuilder
    {
        public static JsonRpcRequest Build(JsonRpcRequest originalRequest, string toolName,
            JObject arguments, string solutionPath)
        {
            if (originalRequest == null || string.IsNullOrWhiteSpace(toolName) || arguments == null ||
                string.IsNullOrWhiteSpace(solutionPath))
                return null;

            var forwardedArguments = (JObject)arguments.DeepClone();
            forwardedArguments["solutionName"] = solutionPath;
            return new JsonRpcRequest
            {
                Id = originalRequest.Id,
                Method = "tools/call",
                Params = new JObject
                {
                    ["name"] = toolName,
                    ["arguments"] = forwardedArguments
                }
            };
        }
    }
}
