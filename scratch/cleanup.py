import re
import os

repo_dir = "/root/FloWave-app/app/src/main/java/com/example/flowave"

# 1. Clean Constants.kt
constants_path = os.path.join(repo_dir, "utils/Constants.kt")
with open(constants_path, "r") as f:
    constants = f.read()

constants = re.sub(r'val PIPED_SEARCH_INSTANCES = listOf\(.*?\)', '', constants, flags=re.DOTALL)
constants = re.sub(r'val INVIDIOUS_SEARCH_INSTANCES = listOf\(.*?\)', '', constants, flags=re.DOTALL)
constants = re.sub(r'val PIPED_STREAM_INSTANCES = listOf\(.*?\)', '', constants, flags=re.DOTALL)
constants = re.sub(r'// Curated from the projects.*?host cooldowns.', '', constants, flags=re.DOTALL)

with open(constants_path, "w") as f:
    f.write(constants)

# 2. Clean InnerTubeRepository.kt
repo_path = os.path.join(repo_dir, "data/remote/InnerTubeRepository.kt")
with open(repo_path, "r") as f:
    repo = f.read()

repo = re.sub(r'private val fallbackPool = ResolverPool\(\)\.apply \{.*?\}', '', repo, flags=re.DOTALL)
repo = re.sub(r'private val fallbackPersistence = .*?ResolverPoolPersistence.*?\n', '', repo)
repo = re.sub(r'private val invidiousDiscovery = .*?InvidiousInstanceDiscovery.*?\n', '', repo)
repo = re.sub(r'private val discoveryMutex = Mutex\(\)\n', '', repo)
repo = re.sub(r'private var lastDiscoveryAttemptMs = 0L\n', '', repo)
repo = re.sub(r'private val selectedFallbackSources = ConcurrentHashMap<String, ResolverCandidate>\(\)\n', '', repo)
repo = re.sub(r'init \{\s*fallbackPersistence\?.load\(\)\?.takeIf \{ it\.isNotBlank\(\) \}\?.let \{ fallbackPool\.restore\(it\) \}\s*\}', '', repo)

# In getStreamResolution, candidate no longer exists
repo = re.sub(r'val candidate = selectedFallbackSources\[videoId\]\n.*?return@withContext ResolvedStreamSource\(url, candidate\.source, candidate\.key\)', 'return@withContext ResolvedStreamSource(url, "inner_tube", null)', repo, flags=re.DOTALL)

# In invalidateStreamUrl
repo = re.sub(r'selectedFallbackSources\.remove\(videoId\)\n', '', repo)

# In searchTracks
repo = re.sub(r'// ENGINE 2: Public Piped Search API Instances.*?if \(tracks\.isNotEmpty\(\)\) return@withContext tracks\s*', '', repo, flags=re.DOTALL)
repo = re.sub(r'// ENGINE 3: Public Invidious Search API Instances.*?if \(tracks\.isNotEmpty\(\)\) return@withContext tracks\s*', '', repo, flags=re.DOTALL)

# In getStreamUrlInternal
repo = re.sub(r'// 1\. Piped Public API Fallback Stream Extraction \(PRIMARY\).*?(?=// 2\. InnerTube Multi-Client Fallback Chain)', '', repo, flags=re.DOTALL)
repo = re.sub(r'// 4\. Invidious Direct Fallback Stream Extraction.*?throw java\.io\.IOException', 'throw java.io.IOException', repo, flags=re.DOTALL)

# Clean up discovery methods
repo = re.sub(r'private suspend fun discoverFallbacks\(\): Boolean.*?return false\s*\}', '', repo, flags=re.DOTALL)
repo = re.sub(r'private suspend fun recordFallbackFailure.*?\}\s*\}', '', repo, flags=re.DOTALL)
repo = re.sub(r'private fun preferredCandidates.*?\}\s*\}', '', repo, flags=re.DOTALL)

# Also remove allowDiscovery parameter
repo = re.sub(r', allowDiscovery: Boolean = true', '', repo)

with open(repo_path, "w") as f:
    f.write(repo)

try:
    os.remove(os.path.join(repo_dir, "data/remote/ResolverPool.kt"))
    os.remove(os.path.join(repo_dir, "data/remote/ResolverStreamSelection.kt"))
except Exception as e:
    print(f"Delete error: {e}")

print("Cleanup complete!")
