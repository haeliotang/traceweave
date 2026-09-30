from __future__ import annotations

import math
import random
from typing import List, Dict, Any, Tuple, Optional, Union

def _euclidean_distance(v1: List[float], v2: List[float]) -> float:
    return math.sqrt(sum((a - b) ** 2 for a, b in zip(v1, v2)))

def _k_means(data: List[List[float]], k: int, max_iters: int = 100) -> Tuple[List[List[float]], List[int]]:
    if not data or k <= 0:
        return [], []
    k = min(k, len(data))
    
    # Initialize centroids by randomly selecting points
    centroids = random.sample(data, k)
    labels = [-1] * len(data)
    
    for _ in range(max_iters):
        new_labels = []
        for point in data:
            distances = [_euclidean_distance(point, c) for c in centroids]
            new_labels.append(distances.index(min(distances)))
            
        if new_labels == labels:
            break
        labels = new_labels
        
        counts = [0] * k
        new_centroids = [[0.0] * len(data[0]) for _ in range(k)]
        
        for point, label in zip(data, labels):
            counts[label] += 1
            for i, val in enumerate(point):
                new_centroids[label][i] += val
                
        for i in range(k):
            if counts[i] > 0:
                new_centroids[i] = [val / counts[i] for val in new_centroids[i]]
            else:
                new_centroids[i] = random.choice(data)
                
        centroids = new_centroids
        
    return centroids, labels

def _silhouette_score(data: List[List[float]], labels: List[int], k: int) -> float:
    if k <= 1 or k >= len(data):
        return -1.0
        
    cluster_points = {i: [] for i in range(k)}
    for idx, label in enumerate(labels):
        cluster_points[label].append(idx)
        
    score_sum = 0.0
    for idx, (point, label) in enumerate(zip(data, labels)):
        points_in_same_cluster = cluster_points[label]
        
        if len(points_in_same_cluster) <= 1:
            a = 0.0
        else:
            a = sum(_euclidean_distance(point, data[other_idx]) for other_idx in points_in_same_cluster if other_idx != idx) / (len(points_in_same_cluster) - 1)
            
        b = float('inf')
        for other_label, other_points in cluster_points.items():
            if other_label == label or not other_points:
                continue
            mean_dist = sum(_euclidean_distance(point, data[other_idx]) for other_idx in other_points) / len(other_points)
            if mean_dist < b:
                b = mean_dist
                
        if a == 0 and b == float('inf'):
            s = 0.0
        else:
            max_ab = max(a, b)
            s = (b - a) / max_ab if max_ab > 0 else 0.0
        score_sum += s
        
    return score_sum / len(data) if data else 0.0

class DataDrivenQuantizer:
    """
    A data-driven quantizer module that replaces hand-crafted threshold-based lattice 
    quantization with unsupervised k-means clustering to discover discrete states.
    """
    
    def __init__(self) -> None:
        self.centroids: List[List[float]] = []
        self.state_tokens: List[str] = []
        
    def fit_discovery_window(self, observations: List[Dict[str, Any]], normalizer: Any, n_clusters: Union[str, int] = 'auto', max_clusters: int = 40) -> None:
        """
        Extracts feature vectors, runs k-means clustering, and assigns state labels based on cluster frequency.
        """
        if not observations:
            return
            
        data = [normalizer.transform(obs)[0] for obs in observations]
        
        best_k = 1
        best_centroids = []
        best_labels = []
        
        num_points = len(data)
        
        if isinstance(n_clusters, str) and n_clusters == 'auto':
            min_k = min(5, num_points - 1) if num_points > 1 else 1
            max_k = min(max_clusters, num_points - 1) if num_points > 1 else 1
            
            if min_k >= 2 and max_k >= min_k:
                best_score = -2.0
                for k in range(min_k, max_k + 1):
                    centroids, labels = _k_means(data, k)
                    score = _silhouette_score(data, labels, k)
                    if score > best_score:
                        best_score = score
                        best_k = k
                        best_centroids = centroids
                        best_labels = labels
            else:
                best_k = min(5, num_points)
                best_centroids, best_labels = _k_means(data, best_k)
        else:
            best_k = int(n_clusters)
            best_k = min(max(1, best_k), num_points)
            best_centroids, best_labels = _k_means(data, best_k)
            
        # Compute cluster frequencies
        counts = {i: 0 for i in range(best_k)}
        for label in best_labels:
            counts[label] += 1
            
        # Sort clusters by frequency (most frequent first)
        sorted_clusters = sorted(counts.keys(), key=lambda k: counts[k], reverse=True)
        
        # Store centroids and create state tokens S01, S02, ...
        self.centroids = [best_centroids[i] for i in sorted_clusters]
        self.state_tokens = [f"S{i+1:02d}" for i in range(len(self.centroids))]
        
    def quantize(self, obs: Dict[str, Any], normalizer: Any) -> Tuple[str, int, float]:
        """
        Maps a new observation to nearest cluster center, returns (state_token, cluster_id, distance_to_centroid).
        """
        if not self.centroids:
            return "S00", -1, 0.0
            
        vector, _ = normalizer.transform(obs)
        distances = [_euclidean_distance(vector, c) for c in self.centroids]
        min_dist = min(distances)
        cluster_id = distances.index(min_dist)
        
        return self.state_tokens[cluster_id], cluster_id, min_dist
        
    def compute_signature(self, obs: Dict[str, Any], normalizer: Any) -> str:
        """
        Returns a signature based on the cluster assignment.
        """
        state_token, _, _ = self.quantize(obs, normalizer)
        return f"STATE={state_token}"
        
    def to_dict(self) -> Dict[str, Any]:
        """
        Serializes the quantizer state.
        """
        return {
            "centroids": self.centroids,
            "state_tokens": self.state_tokens
        }
        
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> DataDrivenQuantizer:
        """
        Deserializes the quantizer state.
        """
        instance = cls()
        instance.centroids = data.get("centroids", [])
        instance.state_tokens = data.get("state_tokens", [])
        return instance
