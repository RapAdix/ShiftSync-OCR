#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <unordered_set>
#include <vector>

namespace {

constexpr double kAngleThreshold = 10.0;
constexpr double kPerpendicularGapFactor = 0.25;

struct Point {
    double x;
    double y;
    bool operator==(const Point& other) const { return x == other.x && y == other.y; }
};

struct Segment {
    Point first;
    Point second;
};

struct PointPairHash {
    std::size_t operator()(const std::pair<Point, Point>& value) const noexcept {
        const auto hashDouble = [](double number) {
            std::uint64_t bits;
            static_assert(sizeof(bits) == sizeof(number), "unexpected double size");
            std::memcpy(&bits, &number, sizeof(bits));
            return std::hash<std::uint64_t>{}(bits);
        };
        return hashDouble(value.first.x) ^ (hashDouble(value.first.y) << 1) ^
               (hashDouble(value.second.x) << 2) ^ (hashDouble(value.second.y) << 3);
    }
};

double distance(const Point& a, const Point& b) {
    return std::hypot(a.x - b.x, a.y - b.y);
}

double pathLength(const std::vector<int>& path, const std::vector<Segment>& segments) {
    if (path.empty()) return 0.0;
    const Segment& first = segments[path.front()];
    const Segment& last = segments[path.back()];
    return distance(first.first, last.second);
}

double distanceToSegment(const Point& p, const Point& a, const Point& b) {
    const double dx = b.x - a.x;
    const double dy = b.y - a.y;
    if (dx == 0.0 && dy == 0.0) return distance(p, a);
    const double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy);
    if (t < 0.0) return distance(p, a);
    if (t > 1.0) return distance(p, b);
    return distance(p, Point{a.x + t * dx, a.y + t * dy});
}

double directionalGapThreshold(double along, double perpendicular, double maximum) {
    const double magnitude = std::hypot(along, perpendicular);
    if (magnitude == 0.0) return maximum;
    const double alignment = std::abs(along) / magnitude;
    const double allowance = kPerpendicularGapFactor +
        (1.0 - kPerpendicularGapFactor) * alignment;
    return maximum * allowance;
}

bool contains(const std::vector<int>& values, int value) {
    return std::find(values.begin(), values.end(), value) != values.end();
}

bool canCombine(const std::vector<int>& path, const std::vector<Segment>& segments,
                const Segment& candidate, double maximumGap, bool horizontal) {
    const Segment& last = segments[path.back()];
    if (horizontal && candidate.second.x <= last.second.x) return false;
    if (!horizontal && candidate.second.y <= last.second.y) return false;

    const Point lastStart = path.size() >= 2 ? segments[path[path.size() - 2]].second : last.first;
    const Point lastEnd = last.second;
    const double activeAngle = std::atan2(lastEnd.y - lastStart.y, lastEnd.x - lastStart.x) * 180.0 / M_PI;
    const double candidateAngle = std::atan2(candidate.second.y - candidate.first.y,
                                             candidate.second.x - candidate.first.x) * 180.0 / M_PI;
    const double angleDifference = std::fmod(std::abs(activeAngle - candidateAngle), 180.0);
    const double normalized = std::min(angleDifference, 180.0 - angleDifference);
    if (normalized > kAngleThreshold) return false;

    const double gapX = candidate.first.x - lastEnd.x;
    const double gapY = candidate.first.y - lastEnd.y;
    const double allowed = directionalGapThreshold(horizontal ? gapX : gapY,
                                                   horizontal ? gapY : gapX,
                                                   maximumGap);
    const bool tipToTail = std::hypot(gapX, gapY) <= allowed;
    const bool overlap = distanceToSegment(candidate.first, lastStart, lastEnd) <=
                         kPerpendicularGapFactor * maximumGap;
    return tipToTail || overlap;
}

void findLongestBranch(int current, int previous, const std::vector<Segment>& segments,
                       const std::unordered_set<int>& used, std::vector<int>& currentPath,
                       std::vector<int>& bestPath,
                       std::unordered_set<std::pair<Point, Point>, PointPairHash>& visited,
                       double threshold, bool horizontal, double backtrack) {
    const double bestLength = pathLength(bestPath, segments);
    const double currentLength = pathLength(currentPath, segments);
    if (bestPath.empty() || currentLength > bestLength) bestPath = currentPath;

    const double updatedBestLength = pathLength(bestPath, segments);
    if (updatedBestLength - currentLength > backtrack &&
        (bestPath.size() < 2 || bestPath[bestPath.size() - 2] != currentPath.back())) return;

    const Segment& last = segments[currentPath.back()];
    const Point lastStart = currentPath.size() >= 2 ? segments[currentPath[currentPath.size() - 2]].second : last.first;
    const Point lastEnd = last.second;
    if (!visited.insert({lastStart, lastEnd}).second) return;

    for (int next = previous; next < static_cast<int>(segments.size()); ++next) {
        if (used.find(next) != used.end() || contains(currentPath, next)) continue;
        const Segment& candidate = segments[next];
        if (horizontal) {
            if (candidate.first.x > lastEnd.x + threshold) break;
        } else if (candidate.first.y > lastEnd.y + threshold) break;
        if (!canCombine(currentPath, segments, candidate, threshold, horizontal)) continue;
        currentPath.push_back(next);
        findLongestBranch(next, current, segments, used, currentPath, bestPath, visited,
                          threshold, horizontal, backtrack);
        currentPath.pop_back();
    }
}

std::vector<std::vector<Point>> mergeOrderedTracks(const std::vector<Segment>& input,
                                                    double threshold, bool horizontal,
                                                    double backtrack) {
    std::vector<Segment> segments = input;
    std::stable_sort(segments.begin(), segments.end(), [horizontal](const Segment& a, const Segment& b) {
        return horizontal ? a.first.x < b.first.x : a.first.y < b.first.y;
    });
    std::vector<std::vector<Point>> result;
    result.reserve(segments.size());
    std::unordered_set<int> used;
    used.reserve(segments.size());
    for (int i = 0; i < static_cast<int>(segments.size()); ++i) {
        if (used.find(i) != used.end()) continue;
        std::vector<int> bestPath;
        std::unordered_set<std::pair<Point, Point>, PointPairHash> visited;
        visited.reserve(segments.size());
        std::vector<int> currentPath{ i };
        findLongestBranch(i, i, segments, used, currentPath, bestPath, visited,
                          threshold, horizontal, backtrack);
        if (bestPath.empty()) bestPath.push_back(i);
        std::vector<Point> points;
        points.push_back(segments[bestPath.front()].first);
        for (int index : bestPath) points.push_back(segments[index].second);
        result.push_back(std::move(points));
        used.insert(bestPath.begin(), bestPath.end());
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_workflowocr_LineDetectorNative_mergeOrderedTracksRaw(
    JNIEnv* env, jobject, jdoubleArray input, jdouble threshold, jboolean horizontal,
    jdouble backtrack) {
    const jsize length = env->GetArrayLength(input);
    std::vector<jdouble> values(static_cast<size_t>(length));
    env->GetDoubleArrayRegion(input, 0, length, values.data());
    std::vector<Segment> segments;
    segments.reserve(static_cast<size_t>(length / 4));
    for (jsize i = 0; i + 3 < length; i += 4) {
        segments.push_back(Segment{{values[i], values[i + 1]}, {values[i + 2], values[i + 3]}});
    }
    const auto merged = mergeOrderedTracks(segments, threshold, horizontal == JNI_TRUE, backtrack);
    std::vector<jdouble> output;
    output.reserve(1 + merged.size() * 3);
    output.push_back(static_cast<double>(merged.size()));
    for (const auto& line : merged) {
        output.push_back(static_cast<double>(line.size()));
        for (const Point& point : line) {
            output.push_back(point.x);
            output.push_back(point.y);
        }
    }
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(output.size()));
    env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
    return result;
}
