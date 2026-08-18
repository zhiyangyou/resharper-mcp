using System;
using System.Collections.Generic;
using System.Linq;

namespace ReSharperMcp
{
    internal static class SolutionRouting
    {
        public static List<SolutionTarget> FindMatches(List<SolutionTarget> all, string selector)
        {
            if (all == null || selector == null)
                return new List<SolutionTarget>();

            var normalizedSelector = SolutionPathIdentity.Normalize(selector);
            var matches = all
                .Where(s => s.Name.Equals(selector, StringComparison.OrdinalIgnoreCase)
                            || s.Path.Equals(selector, StringComparison.OrdinalIgnoreCase)
                            || (normalizedSelector != null &&
                                s.Path.Equals(normalizedSelector, StringComparison.OrdinalIgnoreCase)))
                .ToList();

            if (matches.Count == 1)
                return matches;

            var segmentMatches = all
                .Where(s => PathContainsSegment(s.Path, selector))
                .ToList();
            if (segmentMatches.Count == 1)
                return segmentMatches;
            if (matches.Count == 0 && segmentMatches.Count > 0)
                return segmentMatches;
            return matches;
        }

        private static bool PathContainsSegment(string path, string segment)
        {
            if (string.IsNullOrWhiteSpace(path) || string.IsNullOrWhiteSpace(segment))
                return false;

            var normalized = "/" + path.Replace("\\", "/") + "/";
            var search = "/" + segment.Replace("\\", "/") + "/";
            return normalized.IndexOf(search, StringComparison.OrdinalIgnoreCase) >= 0;
        }

        public static Dictionary<string, string> ComputeDisambiguators(List<NameAndPath> solutions)
        {
            var result = new Dictionary<string, string>(SolutionPathIdentity.Comparer);
            var groups = solutions.GroupBy(s => s.Name, StringComparer.OrdinalIgnoreCase);

            foreach (var group in groups)
            {
                var items = group.ToList();
                if (items.Count <= 1)
                    continue;

                foreach (var item in items)
                {
                    var segments = item.Path.Replace("\\", "/").Split('/');
                    for (var i = segments.Length - 2; i >= 0; i--)
                    {
                        var segment = segments[i];
                        if (string.IsNullOrEmpty(segment))
                            continue;

                        var wrappedSegment = "/" + segment + "/";
                        var matchCount = items.Count(other =>
                            ("/" + other.Path.Replace("\\", "/") + "/")
                                .IndexOf(wrappedSegment, StringComparison.OrdinalIgnoreCase) >= 0);
                        if (matchCount == 1)
                        {
                            result[item.Path] = segment;
                            break;
                        }
                    }
                }
            }

            return result;
        }
    }
}
