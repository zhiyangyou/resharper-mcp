using System;
using System.IO;

namespace ReSharperMcp
{
    internal static class SolutionPathIdentity
    {
        public static StringComparer Comparer => StringComparer.OrdinalIgnoreCase;

        public static string Normalize(string path)
        {
            if (string.IsNullOrWhiteSpace(path))
                return null;

            try
            {
                var fullPath = Path.GetFullPath(path);
                var root = Path.GetPathRoot(fullPath);
                if (!string.IsNullOrEmpty(root) && !fullPath.Equals(root, StringComparison.OrdinalIgnoreCase))
                    fullPath = fullPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
                return fullPath;
            }
            catch (Exception)
            {
                return null;
            }
        }
    }
}
