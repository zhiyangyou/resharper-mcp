using System;
using System.Collections.Generic;
using System.Linq;
using Newtonsoft.Json.Linq;
using ReSharperMcp.Protocol;
using Xunit;

namespace ReSharperMcp.Tests
{
    public class SolutionRoutingTests
    {
        private const string SearchSymbol = "search_symbol";

        [Fact]
        public void SameNameSolutionsKeepDistinctStableIds()
        {
            var targets = CreateTargets();

            Assert.True(targets.Select(target => target.Path)
                .Distinct(SolutionPathIdentity.Comparer)
                .Count() == 2);
            var matches = SolutionRouting.FindMatches(targets, targets[0].Path);
            Assert.Single(matches);
            Assert.Same(targets[0], matches.Single());
        }

        [Fact]
        public void FullPathSelectorRoutesToTheMatchingSolution()
        {
            var targets = CreateTargets();
            var matches = SolutionRouting.FindMatches(targets, targets[1].Path);

            Assert.Single(matches);
            Assert.Contains("idlexX55555", matches[0].Path);
        }

        [Fact]
        public void NameSelectorRemainsAmbiguousForDuplicateNames()
        {
            var matches = SolutionRouting.FindMatches(CreateTargets(), "Client");

            Assert.Equal(2, matches.Count);
        }

        [Fact]
        public void UniquePathSegmentSelectsOneSolution()
        {
            var matches = SolutionRouting.FindMatches(CreateTargets(), "idlexX55555");

            Assert.Single(matches);
            Assert.Contains("idlexX55555", matches[0].Path);
        }

        [Fact]
        public void PeerRequestCarriesResolvedStableIdWithoutMutatingOriginalArguments()
        {
            var original = new JsonRpcRequest
            {
                Id = 7,
                Method = "tools/call",
                Params = new JObject
                {
                    ["name"] = SearchSymbol,
                    ["arguments"] = new JObject { ["query"] = "Client" }
                }
            };

            var request = PeerRequestBuilder.Build(
                original,
                SearchSymbol,
                (JObject)original.Params["arguments"],
                @"G:\_m88_work_space\idlexX55555\Client\Client.sln");

            Assert.NotNull(request);
            Assert.Null(((JObject)original.Params["arguments"])["solutionName"]);
            Assert.Equal(@"G:\_m88_work_space\idlexX55555\Client\Client.sln",
                request.Params["arguments"]["solutionName"].Value<string>());
        }

        [Fact]
        public void PathIdentityNormalizesSeparatorsAndCaseInsensitiveKeys()
        {
            var windowsPath = SolutionPathIdentity.Normalize(@"G:\_m88_work_space\idlexX55555\Client\Client.sln");
            var slashPath = SolutionPathIdentity.Normalize("G:/_m88_work_space/idlexX55555/Client/Client.sln");

            Assert.Equal(windowsPath, slashPath, SolutionPathIdentity.Comparer);
        }

        private static List<SolutionTarget> CreateTargets()
        {
            return new List<SolutionTarget>
            {
                new SolutionTarget
                {
                    Name = "Client",
                    Path = SolutionPathIdentity.Normalize("D:/_workSpace/m88/idlexX/Client/Client.sln"),
                    IsLocal = true
                },
                new SolutionTarget
                {
                    Name = "Client",
                    Path = SolutionPathIdentity.Normalize("G:/_m88_work_space/idlexX55555/Client/Client.sln"),
                    IsLocal = false,
                    PeerPort = 23742
                }
            };
        }
    }
}
