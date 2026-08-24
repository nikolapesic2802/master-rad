package xyz.marsavic.gfxlab.gpu;

public final class GpuKernelSources {

	private GpuKernelSources() {
	}

	public static final String PATH_TRACER = """
#include <cuda_runtime.h>
#include <math_constants.h>

#define EPSILON 1e-4f
#ifndef BVH_STACK_SIZE
#define BVH_STACK_SIZE 32
#endif
#ifndef GFXLAB_RENDER_METRICS
#define GFXLAB_RENDER_METRICS 0
#endif
#define REF_TYPE_MASK 0xC0000000
#define REF_INDEX_MASK 0x3FFFFFFF
#define REF_TYPE_SPHERE 0x00000000
#define REF_TYPE_BOX 0x40000000
#define REF_TYPE_AFFINE_SPHERE 0x80000000
#define REF_TYPE_AFFINE_BOX 0xC0000000
#define BVH_LEAF_KIND_MIXED -1
#define BVH_LEAF_KIND_SPHERE -2
#define BVH_LEAF_KIND_BOX -3
#define BVH_LEAF_KIND_AFFINE_SPHERE -4
#define BVH_LEAF_KIND_AFFINE_BOX -5
#define STAT_RAYS 0
#define STAT_AABB_TESTS 1
#define STAT_PRIMITIVE_TESTS 2
#define STAT_SPHERE_TESTS 3
#define STAT_BOX_TESTS 4
#define STAT_PLANE_TESTS 5
#define STAT_AFFINE_SPHERE_TESTS 6
#define STAT_AFFINE_BOX_TESTS 7
#define STAT_STACK_OVERFLOWS 8
#define STAT_MAX_STACK_SIZE 9
#define STAT_INTERNAL_NODES 10
#define STAT_LEAF_NODES 11
#define STAT_HOMOGENEOUS_LEAF_NODES 12
#define STAT_MIXED_LEAF_NODES 13

__device__ __host__ inline float3 make_vec(float x, float y, float z) {
	return make_float3(x, y, z);
}

__device__ __host__ inline float3 operator+(float3 a, float3 b) {
	return make_float3(a.x + b.x, a.y + b.y, a.z + b.z);
}

__device__ __host__ inline float3 operator-(float3 a, float3 b) {
	return make_float3(a.x - b.x, a.y - b.y, a.z - b.z);
}

__device__ __host__ inline float3 operator-(float3 v) {
	return make_float3(-v.x, -v.y, -v.z);
}

__device__ __host__ inline float3 operator*(float3 v, float s) {
	return make_float3(v.x * s, v.y * s, v.z * s);
}

__device__ __host__ inline float3 operator*(float s, float3 v) {
	return v * s;
}

__device__ __host__ inline float3 operator*(float3 a, float3 b) {
	return make_float3(a.x * b.x, a.y * b.y, a.z * b.z);
}

__device__ __host__ inline float3& operator+=(float3 &a, const float3 &b) {
	a.x += b.x;
	a.y += b.y;
	a.z += b.z;
	return a;
}

__device__ __host__ inline float3& operator-=(float3 &a, const float3 &b) {
	a.x -= b.x;
	a.y -= b.y;
	a.z -= b.z;
	return a;
}

__device__ __host__ inline float3 operator/(float3 v, float s) {
	float inv = 1.0f / s;
	return make_float3(v.x * inv, v.y * inv, v.z * inv);
}

__device__ __host__ inline float dot(const float3 &a, const float3 &b) {
	return a.x * b.x + a.y * b.y + a.z * b.z;
}

__device__ __host__ inline float3 normalizeSafe(float3 v) {
	float len = sqrtf(fmaxf(dot(v, v), 1e-20f));
	return v / len;
}

__device__ inline float3 normalizeTransformedNormal(float3 v) {
	float lengthSquared = dot(v, v);
	if (lengthSquared >= 1.0e-20f && isfinite(lengthSquared)) {
		return v / sqrtf(lengthSquared);
	}
	float scale = fmaxf(fabsf(v.x), fmaxf(fabsf(v.y), fabsf(v.z)));
	if (!(scale > 0.0f) || !isfinite(scale)) return make_float3(0.0f, 0.0f, 0.0f);
	float3 scaled = make_float3(v.x / scale, v.y / scale, v.z / scale);
	return scaled / sqrtf(dot(scaled, scaled));
}

__device__ inline float3 cross(const float3 &a, const float3 &b) {
	return make_float3(
			a.y * b.z - a.z * b.y,
			a.z * b.x - a.x * b.z,
			a.x * b.y - a.y * b.x
	);
}

struct Material {
	float3 diffuse;
	float3 reflective;
	float3 refractive;
	float3 emittance;
	float refractiveIndex;
};

struct GridData {
	int hasGrid;
	float2 size;
	float2 line;
	int cellMaterialIndex;
	int lineMaterialIndex;
};

struct PlaneData {
	float3 normal;
	float planeOffset;
	float3 point;
	float3 e;
	float3 f;
	float eLenSq;
	float fLenSq;
	float e_f;
	float f_e;
	float sinSq;
	int surfaceMaterialIndex;
	GridData grid;
};

struct HitInfo {
	int hit;
	float t;
	int primitiveOrder;
	int materialIndex;
	float3 position;
	float3 normal;
	Material material;
};

struct Rng {
	unsigned int state;
};

__device__ inline unsigned int hash_u32(unsigned int x) {
	x ^= x >> 17;
	x *= 0xed5ad4bbU;
	x ^= x >> 11;
	x *= 0xac4c1b51U;
	x ^= x >> 15;
	x *= 0x31848babU;
	x ^= x >> 14;
	return x;
}

__device__ inline float rand01(Rng *rng) {
	rng->state = hash_u32(rng->state + 0x9e3779b9U);
	return (rng->state >> 8) * 0x1p-24f;
}

__device__ inline float luminance(float3 c) {
	return c.x * 0.212655f + c.y * 0.715158f + c.z * 0.072187f;
}

__device__ inline float positiveModulo(float x, float y) {
	return x - y * floorf(x / y);
}

__device__ inline void writeMaterial(Material *dst, const float *src) {
	dst->diffuse = make_vec(src[0], src[1], src[2]);
	dst->reflective = make_vec(src[3], src[4], src[5]);
	dst->refractive = make_vec(src[6], src[7], src[8]);
	dst->emittance = make_vec(src[9], src[10], src[11]);
	dst->refractiveIndex = src[12];
}

__device__ inline Material loadMaterial(const float *base, int offset) {
	Material m;
	writeMaterial(&m, base + offset);
	return m;
}

__device__ inline Material loadMaterialByIndex(const float *materials, int index) {
	Material m{};
	if (index < 0) {
		return m;
	}
	constexpr int FLOATS_PER_MATERIAL = 13;
	int offset = index * FLOATS_PER_MATERIAL;
	writeMaterial(&m, materials + offset);
	return m;
}

__device__ inline GridData loadGrid(const float *base, int offset) {
	GridData grid{};
	if (base[offset] == 0.0f) {
		grid.hasGrid = 0;
		grid.cellMaterialIndex = -1;
		grid.lineMaterialIndex = -1;
		return grid;
	}
	grid.hasGrid = 1;
	grid.size = make_float2(base[offset + 1], base[offset + 2]);
	grid.line = make_float2(base[offset + 3], base[offset + 4]);
	grid.cellMaterialIndex = (int) base[offset + 6];
	grid.lineMaterialIndex = (int) base[offset + 7];
	return grid;
}

__device__ inline PlaneData loadPlane(const float *data, int index) {
	constexpr int FLOATS_PER_PLANE = 26;

	int offset = index * FLOATS_PER_PLANE;
	PlaneData plane{};
	plane.normal = make_vec(data[offset + 0], data[offset + 1], data[offset + 2]);
	plane.planeOffset = data[offset + 3];
	plane.point = make_vec(data[offset + 4], data[offset + 5], data[offset + 6]);
	plane.e = make_vec(data[offset + 7], data[offset + 8], data[offset + 9]);
	plane.f = make_vec(data[offset + 10], data[offset + 11], data[offset + 12]);
	plane.eLenSq = data[offset + 13];
	plane.fLenSq = data[offset + 14];
	plane.e_f = data[offset + 15];
	plane.f_e = data[offset + 16];
	plane.sinSq = data[offset + 17];
	int gridOffset = offset + 18;
	plane.surfaceMaterialIndex = (int) data[gridOffset + 5];
	plane.grid = loadGrid(data, gridOffset);
	return plane;
}

__device__ inline int choosePlaneMaterialIndex(const PlaneData &plane, float3 hitPoint) {
	if (!plane.grid.hasGrid) {
		return plane.surfaceMaterialIndex;
	}
	float3 rel = hitPoint - plane.point;
	float b_e = dot(rel, plane.e) / plane.eLenSq;
	float b_f = dot(rel, plane.f) / plane.fLenSq;
	float u = (b_e - b_f * plane.f_e) / plane.sinSq;
	float v = (b_f - b_e * plane.e_f) / plane.sinSq;

	float halfLineX = plane.grid.line.x * 0.5f;
	float halfLineY = plane.grid.line.y * 0.5f;
	float modX = positiveModulo(u + halfLineX, plane.grid.size.x);
	float modY = positiveModulo(v + halfLineY, plane.grid.size.y);
	if (modX < plane.grid.line.x || modY < plane.grid.line.y) {
		return plane.grid.lineMaterialIndex;
	}
	return plane.grid.cellMaterialIndex;
}

__device__ inline Material choosePlaneMaterial(
		const PlaneData &plane, const float *materials, float3 hitPoint) {
	return loadMaterialByIndex(materials, choosePlaneMaterialIndex(plane, hitPoint));
}

__device__ inline float affineRayShift(const float *data, int offset, float3 origin, float3 dir) {
	float3 center = make_vec(data[offset + 3], data[offset + 7], data[offset + 11]);
	float denominator = dot(dir, dir);
	return denominator > 0.0f ? fmaxf(0.0f, dot(center - origin, dir) / denominator) : 0.0f;
}

__device__ inline float3 applyAffineShiftedPoint(const float *data, int offset,
		float3 origin, float3 dir, float shift) {
	float3 relative = make_vec(
			fmaf(dir.x, shift, origin.x - data[offset + 3]),
			fmaf(dir.y, shift, origin.y - data[offset + 7]),
			fmaf(dir.z, shift, origin.z - data[offset + 11]));
	return make_vec(
			data[offset + 0] * relative.x + data[offset + 1] * relative.y + data[offset + 2] * relative.z,
			data[offset + 4] * relative.x + data[offset + 5] * relative.y + data[offset + 6] * relative.z,
			data[offset + 8] * relative.x + data[offset + 9] * relative.y + data[offset + 10] * relative.z
	);
}

__device__ inline float3 applyAffineVector(const float *data, int offset, float3 v) {
	return make_vec(
			data[offset + 0] * v.x + data[offset + 1] * v.y + data[offset + 2] * v.z,
			data[offset + 4] * v.x + data[offset + 5] * v.y + data[offset + 6] * v.z,
			data[offset + 8] * v.x + data[offset + 9] * v.y + data[offset + 10] * v.z
	);
}

__device__ inline float3 applyLinear9(const float *data, int offset, float3 v) {
	return make_vec(
			data[offset + 0] * v.x + data[offset + 1] * v.y + data[offset + 2] * v.z,
			data[offset + 3] * v.x + data[offset + 4] * v.y + data[offset + 5] * v.z,
			data[offset + 6] * v.x + data[offset + 7] * v.y + data[offset + 8] * v.z
	);
}

struct DirectionFrame {
	bool valid;
	float3 unit;
	float inverseLength;
};

__device__ inline DirectionFrame normalizedDirection(float3 direction) {
	float lengthSquared = dot(direction, direction);
	if (isfinite(lengthSquared) && fabsf(lengthSquared - 1.0f) <= 2.0e-6f) {
		return DirectionFrame{true, direction, 1.0f};
	}
	float scale = fmaxf(fabsf(direction.x),
			fmaxf(fabsf(direction.y), fabsf(direction.z)));
	if (!(scale > 0.0f) || !isfinite(scale)) {
		return DirectionFrame{false, make_vec(0.0f, 0.0f, 0.0f), 0.0f};
	}
	float3 scaled = make_vec(
			direction.x / scale, direction.y / scale, direction.z / scale);
	float scaledLength = sqrtf(dot(scaled, scaled));
	float directionLength = scale * scaledLength;
	float inverseLength = 1.0f / directionLength;
	if (!(inverseLength > 0.0f) || !isfinite(inverseLength)) {
		return DirectionFrame{false, make_vec(0.0f, 0.0f, 0.0f), 0.0f};
	}
	return DirectionFrame{true, scaled * (1.0f / scaledLength), inverseLength};
}

__device__ inline bool traceSphere(const float *spheres, int count, const float *materials,
		float3 origin, float3 dir, int orderBase, HitInfo *hit) {
	constexpr int FLOATS_PER_SPHERE = 5;
	bool found = false;
	float closestT = hit->t;
	for (int i = 0; i < count; ++i) {
		int offset = i * FLOATS_PER_SPHERE;
		float3 center = make_vec(spheres[offset + 0], spheres[offset + 1], spheres[offset + 2]);
		float radius = spheres[offset + 3];
		int materialIndex = (int) spheres[offset + 4];
		float3 oc = origin - center;
		float a = dot(dir, dir);
		float b = dot(oc, dir);
		float centerT = -b / a;
		float3 closest = make_vec(
				fmaf(dir.x, centerT, oc.x),
				fmaf(dir.y, centerT, oc.y),
				fmaf(dir.z, centerT, oc.z));
		// Reproject once: on tiny distant spheres, a small longitudinal residual
		// would otherwise dominate the recovered surface normal.
		float centerCorrection = -dot(closest, dir) / a;
		centerT += centerCorrection;
		closest = make_vec(
				fmaf(dir.x, centerCorrection, closest.x),
				fmaf(dir.y, centerCorrection, closest.y),
				fmaf(dir.z, centerCorrection, closest.z));
		float radialSquared = fmaf(radius, radius, -dot(closest, closest));
		if (radialSquared <= 0.0f) {
			continue;
		}
		float halfSpan = sqrtf(radialSquared / a);
		float t1 = centerT - halfSpan;
		float t2 = centerT + halfSpan;
		float hitT = 0.0f;
		float radialOffset = 0.0f;
		if (t1 > EPSILON) {
			hitT = t1;
			radialOffset = -halfSpan;
		} else if (t2 > EPSILON) {
			hitT = t2;
			radialOffset = halfSpan;
		} else {
			continue;
		}
		if (hitT < closestT) {
			closestT = hitT;
			hit->hit = 1;
			hit->t = hitT;
			hit->position = origin + dir * hitT;
			float3 radial = make_vec(
					fmaf(dir.x, radialOffset, closest.x),
					fmaf(dir.y, radialOffset, closest.y),
					fmaf(dir.z, radialOffset, closest.z));
			hit->normal = normalizeSafe(radial);
			hit->material = loadMaterialByIndex(materials, materialIndex);
			hit->materialIndex = materialIndex;
			hit->primitiveOrder = orderBase + i;
			found = true;
		}
	}
	return found;
}

__device__ inline bool traceAffineSphere(const float *affineSpheres, int count,
		const float *materials, float3 origin, float3 dir, int orderBase, HitInfo *hit) {
	constexpr int FLOATS_PER_AFFINE_SPHERE = 22;
	bool found = false;
	float closestT = hit->t;
	for (int i = 0; i < count; ++i) {
		int offset = i * FLOATS_PER_AFFINE_SPHERE;
		float rayShift = affineRayShift(affineSpheres, offset, origin, dir);
		float3 localOrigin = applyAffineShiftedPoint(affineSpheres, offset, origin, dir, rayShift);
		float3 localDir = applyAffineVector(affineSpheres, offset, dir);
		float afterLocal = EPSILON - rayShift;
		float solveA = dot(localDir, localDir);
		float3 solveDir = localDir;
		float parameterScale = 1.0f;
		if (!(solveA > 0.0f) || !isfinite(solveA)) {
			DirectionFrame direction = normalizedDirection(localDir);
			if (!direction.valid) continue;
			solveDir = direction.unit;
			solveA = 1.0f;
			parameterScale = direction.inverseLength;
		}
		float scaledAfterT = afterLocal / parameterScale;
		float centerT = -dot(localOrigin, solveDir) / solveA;
		float3 closest = localOrigin + solveDir * centerT;
		float radialSquared = fmaf(-closest.x, closest.x,
				fmaf(-closest.y, closest.y, fmaf(-closest.z, closest.z, 1.0f)));
		if (radialSquared <= 0.0f) {
			continue;
		}
		float halfSpan = sqrtf(radialSquared / solveA);
		float t1 = centerT - halfSpan;
		float t2 = centerT + halfSpan;
		float scaledHitT = 0.0f;
		if (t1 > scaledAfterT) {
			scaledHitT = t1;
		} else if (t2 > scaledAfterT) {
			scaledHitT = t2;
		} else {
			continue;
		}
		float hitT = scaledHitT * parameterScale;
		float worldT = rayShift + hitT;
		if (worldT >= closestT) {
			continue;
		}
		float3 localHit = localOrigin + localDir * hitT;
		float3 worldNormal = normalizeTransformedNormal(
				applyLinear9(affineSpheres, offset + 12, localHit));
		int materialIndex = (int) affineSpheres[offset + 21];
		hit->hit = 1;
		hit->t = worldT;
		hit->position = origin + dir * worldT;
		hit->normal = worldNormal;
		hit->material = loadMaterialByIndex(materials, materialIndex);
		hit->materialIndex = materialIndex;
		hit->primitiveOrder = orderBase + i;
		closestT = worldT;
		found = true;
	}
	return found;
}

__device__ inline bool traceAffineBox(const float *affineBoxes, int count,
		const float *materials, float3 origin, float3 dir, int orderBase, HitInfo *hit) {
	constexpr int FLOATS_PER_AFFINE_BOX = 22;
	bool found = false;
	float closestT = hit->t;
	for (int i = 0; i < count; ++i) {
		int offset = i * FLOATS_PER_AFFINE_BOX;
		float rayShift = affineRayShift(affineBoxes, offset, origin, dir);
		float3 localOrigin = applyAffineShiftedPoint(affineBoxes, offset, origin, dir, rayShift);
		float3 localDir = applyAffineVector(affineBoxes, offset, dir);
		float afterLocal = EPSILON - rayShift;

		float txMin, txMax;
		if (localDir.x == 0.0f) {
			if (localOrigin.x < -1.0f || localOrigin.x > 1.0f) {
				continue;
			}
			txMin = -CUDART_INF_F;
			txMax = CUDART_INF_F;
		} else {
			float tx1 = (-1.0f - localOrigin.x) / localDir.x;
			float tx2 = (1.0f - localOrigin.x) / localDir.x;
			txMin = fminf(tx1, tx2);
			txMax = fmaxf(tx1, tx2);
		}

		float tyMin, tyMax;
		if (localDir.y == 0.0f) {
			if (localOrigin.y < -1.0f || localOrigin.y > 1.0f) {
				continue;
			}
			tyMin = -CUDART_INF_F;
			tyMax = CUDART_INF_F;
		} else {
			float ty1 = (-1.0f - localOrigin.y) / localDir.y;
			float ty2 = (1.0f - localOrigin.y) / localDir.y;
			tyMin = fminf(ty1, ty2);
			tyMax = fmaxf(ty1, ty2);
		}

		float tzMin, tzMax;
		if (localDir.z == 0.0f) {
			if (localOrigin.z < -1.0f || localOrigin.z > 1.0f) {
				continue;
			}
			tzMin = -CUDART_INF_F;
			tzMax = CUDART_INF_F;
		} else {
			float tz1 = (-1.0f - localOrigin.z) / localDir.z;
			float tz2 = (1.0f - localOrigin.z) / localDir.z;
			tzMin = fminf(tz1, tz2);
			tzMax = fmaxf(tz1, tz2);
		}

		float tEnter = fmaxf(fmaxf(txMin, tyMin), tzMin);
		float tExit = fminf(fminf(txMax, tyMax), tzMax);
		if (tEnter >= tExit || tExit <= afterLocal) {
			continue;
		}

		int axisEnter = 0;
		float enterValue = txMin;
		if (tyMin > enterValue) {
			enterValue = tyMin;
			axisEnter = 1;
		}
		if (tzMin > enterValue) {
			axisEnter = 2;
		}

		int axisExit = 0;
		float exitValue = txMax;
		if (tyMax < exitValue) {
			exitValue = tyMax;
			axisExit = 1;
		}
		if (tzMax < exitValue) {
			axisExit = 2;
		}

		float candidateT;
		int normalAxis;
		float normalSign;
		if (tEnter > afterLocal) {
			candidateT = tEnter;
			normalAxis = axisEnter;
			float dirComponent = (normalAxis == 0) ? localDir.x : (normalAxis == 1 ? localDir.y : localDir.z);
			normalSign = dirComponent > 0.0f ? -1.0f : 1.0f;
		} else {
			candidateT = tExit;
			normalAxis = axisExit;
			float dirComponent = (normalAxis == 0) ? localDir.x : (normalAxis == 1 ? localDir.y : localDir.z);
			normalSign = dirComponent >= 0.0f ? 1.0f : -1.0f;
		}

		float worldT = rayShift + candidateT;
		if (worldT >= closestT) {
			continue;
		}

		float3 localNormal;
		if (normalAxis == 0) {
			localNormal = make_vec(normalSign, 0.0f, 0.0f);
		} else if (normalAxis == 1) {
			localNormal = make_vec(0.0f, normalSign, 0.0f);
		} else {
			localNormal = make_vec(0.0f, 0.0f, normalSign);
		}

		float3 worldNormal = normalizeTransformedNormal(
				applyLinear9(affineBoxes, offset + 12, localNormal));
		int materialIndex = (int) affineBoxes[offset + 21];
		hit->hit = 1;
		hit->t = worldT;
		hit->position = origin + dir * worldT;
		hit->normal = worldNormal;
		hit->material = loadMaterialByIndex(materials, materialIndex);
		hit->materialIndex = materialIndex;
		hit->primitiveOrder = orderBase + i;
		closestT = worldT;
		found = true;
	}
	return found;
}

__device__ inline bool traceBox(const float *boxes, int count, const float *materials,
		float3 origin, float3 dir, int orderBase, HitInfo *hit) {
	constexpr int FLOATS_PER_BOX = 7;
	bool found = false;
	float closestT = hit->t;
	for (int i = 0; i < count; ++i) {
		int offset = i * FLOATS_PER_BOX;
		float3 bMin = make_vec(boxes[offset + 0], boxes[offset + 1], boxes[offset + 2]);
		float3 bMax = make_vec(boxes[offset + 3], boxes[offset + 4], boxes[offset + 5]);
		int materialIndex = (int) boxes[offset + 6];

		float txMin, txMax;
		if (dir.x == 0.0f) {
			if (origin.x < bMin.x || origin.x > bMax.x) {
				continue;
			}
			txMin = -CUDART_INF_F;
			txMax = CUDART_INF_F;
		} else {
			float tx1 = (bMin.x - origin.x) / dir.x;
			float tx2 = (bMax.x - origin.x) / dir.x;
			txMin = fminf(tx1, tx2);
			txMax = fmaxf(tx1, tx2);
		}

		float tyMin, tyMax;
		if (dir.y == 0.0f) {
			if (origin.y < bMin.y || origin.y > bMax.y) {
				continue;
			}
			tyMin = -CUDART_INF_F;
			tyMax = CUDART_INF_F;
		} else {
			float ty1 = (bMin.y - origin.y) / dir.y;
			float ty2 = (bMax.y - origin.y) / dir.y;
			tyMin = fminf(ty1, ty2);
			tyMax = fmaxf(ty1, ty2);
		}

		float tzMin, tzMax;
		if (dir.z == 0.0f) {
			if (origin.z < bMin.z || origin.z > bMax.z) {
				continue;
			}
			tzMin = -CUDART_INF_F;
			tzMax = CUDART_INF_F;
		} else {
			float tz1 = (bMin.z - origin.z) / dir.z;
			float tz2 = (bMax.z - origin.z) / dir.z;
			tzMin = fminf(tz1, tz2);
			tzMax = fmaxf(tz1, tz2);
		}

		float tEnter = fmaxf(fmaxf(txMin, tyMin), tzMin);
		float tExit = fminf(fminf(txMax, tyMax), tzMax);
		if (tEnter >= tExit || tExit <= EPSILON) {
			continue;
		}

		int axisEnter = 0;
		float enterValue = txMin;
		if (tyMin > enterValue) {
			enterValue = tyMin;
			axisEnter = 1;
		}
		if (tzMin > enterValue) {
			axisEnter = 2;
		}

		int axisExit = 0;
		float exitValue = txMax;
		if (tyMax < exitValue) {
			exitValue = tyMax;
			axisExit = 1;
		}
		if (tzMax < exitValue) {
			axisExit = 2;
		}

		float candidateT;
		int normalAxis;
		float normalSign;
		if (tEnter > EPSILON) {
			candidateT = tEnter;
			normalAxis = axisEnter;
			float dirComponent = (normalAxis == 0) ? dir.x : (normalAxis == 1 ? dir.y : dir.z);
			normalSign = dirComponent > 0.0f ? -1.0f : 1.0f;
		} else {
			candidateT = tExit;
			normalAxis = axisExit;
			float dirComponent = (normalAxis == 0) ? dir.x : (normalAxis == 1 ? dir.y : dir.z);
			normalSign = dirComponent >= 0.0f ? 1.0f : -1.0f;
		}

		if (candidateT >= closestT) {
			continue;
		}

		float3 normal;
		if (normalAxis == 0) {
			normal = make_vec(normalSign, 0.0f, 0.0f);
		} else if (normalAxis == 1) {
			normal = make_vec(0.0f, normalSign, 0.0f);
		} else {
			normal = make_vec(0.0f, 0.0f, normalSign);
		}

		hit->hit = 1;
		hit->t = candidateT;
		hit->position = origin + dir * candidateT;
		hit->normal = normal;
		hit->material = loadMaterialByIndex(materials, materialIndex);
		hit->materialIndex = materialIndex;
		hit->primitiveOrder = orderBase + i;
		closestT = candidateT;
		found = true;
	}
	return found;
}

__device__ inline bool tracePlane(const float *planes, int count, const float *materials,
		float3 origin, float3 dir, int orderBase, HitInfo *hit) {
	bool found = false;
	float closestT = hit->t;
	for (int i = 0; i < count; ++i) {
		PlaneData plane = loadPlane(planes, i);
		float denom = dot(plane.normal, dir);
		if (denom == 0.0f) {
			continue;
		}
		float t = -(dot(plane.normal, origin) + plane.planeOffset) / denom;
		if (t <= EPSILON || t >= closestT) {
			continue;
		}
		float3 p = origin + dir * t;
		hit->hit = 1;
		hit->t = t;
		hit->position = p;
		hit->normal = plane.normal;
		hit->materialIndex = choosePlaneMaterialIndex(plane, p);
		hit->material = loadMaterialByIndex(materials, hit->materialIndex);
		hit->primitiveOrder = orderBase + i;
		closestT = t;
		found = true;
	}
	return found;
}

__device__ inline float bvhEntryDistance(const float *bounds, int nodeIndex,
		float3 origin, float3 dir, float beforeT) {
	int offset = nodeIndex * 6;
	float3 bMin = make_vec(bounds[offset], bounds[offset + 1], bounds[offset + 2]);
	float3 bMax = make_vec(bounds[offset + 3], bounds[offset + 4], bounds[offset + 5]);
	float tMin = EPSILON;
	// Keep equal-distance candidates reachable across different BVH topologies.
	// The same gamma-style expansion used for slab far distances covers float
	// round-off in a conservative node bound without admitting materially
	// farther geometry.
	float conservativeBeforeT = isfinite(beforeT)
			? fmaf(beforeT, 1.0000015f, 1.0e-6f)
			: beforeT;
	float tMax = conservativeBeforeT;
	for (int axis = 0; axis < 3; axis++) {
		float o = axis == 0 ? origin.x : (axis == 1 ? origin.y : origin.z);
		float d = axis == 0 ? dir.x : (axis == 1 ? dir.y : dir.z);
		float lo = axis == 0 ? bMin.x : (axis == 1 ? bMin.y : bMin.z);
		float hi = axis == 0 ? bMax.x : (axis == 1 ? bMax.y : bMax.z);
		if (d == 0.0f) {
			if (o < lo || o > hi) return CUDART_INF_F;
			continue;
		}
		float t0 = (lo - o) / d;
		float t1 = (hi - o) / d;
		if (t0 > t1) { float tmp = t0; t0 = t1; t1 = tmp; }
		// Conservative far distance for float round-off in the slab test.
		// This is the gamma(3) remedy used by robust ray-box traversal.
		if (t1 > 0.0f) t1 *= 1.00000072f;
		tMin = fmaxf(tMin, t0);
		tMax = fminf(tMax, t1);
		if (tMax < tMin) return CUDART_INF_F;
	}
	return tMin <= conservativeBeforeT ? tMin : CUDART_INF_F;
}

__device__ inline bool tracePrimitiveRef(int ref, int type,
		const float *spheres, const float *boxes,
		const float *affineSpheres, const float *affineBoxes,
		int sphereCount, int boxCount, int affineSphereCount,
		const float *materials, float3 origin, float3 dir, HitInfo *hit) {
	int index = ref & REF_INDEX_MASK;
	HitInfo candidate{};
	// Let each primitive reject roots beyond the current closest hit. One ULP
	// of headroom preserves the deterministic equal-distance order contract.
	candidate.t = nextafterf(hit->t, CUDART_INF_F);
	bool candidateFound;
	int order;
	if (type == REF_TYPE_BOX) {
		order = sphereCount + index;
		candidateFound = traceBox(
				boxes + index * 7, 1, materials, origin, dir, order, &candidate);
	} else if (type == REF_TYPE_AFFINE_SPHERE) {
		order = sphereCount + boxCount + index;
		candidateFound = traceAffineSphere(
				affineSpheres + index * 22, 1, materials, origin, dir, order, &candidate);
	} else if (type == REF_TYPE_AFFINE_BOX) {
		order = sphereCount + boxCount + affineSphereCount + index;
		candidateFound = traceAffineBox(
				affineBoxes + index * 22, 1, materials, origin, dir, order, &candidate);
	} else {
		order = index;
		candidateFound = traceSphere(
				spheres + index * 5, 1, materials, origin, dir, order, &candidate);
	}
	if (!candidateFound || candidate.t > hit->t
			|| (candidate.t == hit->t && order >= hit->primitiveOrder)) return false;
	*hit = candidate;
	hit->primitiveOrder = order;
	return true;
}

__device__ inline bool traceBvhLeaf(int leafKind, int first, int count,
		const int *primitiveRefs, int primitiveRefCount,
		const float *spheres, const float *boxes,
		const float *affineSpheres, const float *affineBoxes,
		int sphereCount, int boxCount, int affineSphereCount,
		const float *materials, float3 origin, float3 dir, HitInfo *hit,
		unsigned long long *localPrimitiveTests, unsigned long long *localTypeTests) {
	if (localPrimitiveTests != nullptr) *localPrimitiveTests += (unsigned long long) count;
	bool found = false;
	// Decode a homogeneous leaf once. Primitive dispatch remains in one shared
	// loop to keep every render kernel compact.
	int leafType = leafKind == BVH_LEAF_KIND_SPHERE ? REF_TYPE_SPHERE
			: leafKind == BVH_LEAF_KIND_BOX ? REF_TYPE_BOX
			: leafKind == BVH_LEAF_KIND_AFFINE_SPHERE ? REF_TYPE_AFFINE_SPHERE
			: leafKind == BVH_LEAF_KIND_AFFINE_BOX ? REF_TYPE_AFFINE_BOX
			: BVH_LEAF_KIND_MIXED;
	for (int i = 0; i < count; i++) {
		int refIndex = first + i;
		if (refIndex < 0 || refIndex >= primitiveRefCount) continue;
		int ref = primitiveRefs[refIndex];
		int type = leafType == BVH_LEAF_KIND_MIXED ? ref & REF_TYPE_MASK : leafType;
		if (localTypeTests != nullptr) {
			int typeIndex = type == REF_TYPE_BOX ? 1
					: type == REF_TYPE_AFFINE_SPHERE ? 3
					: type == REF_TYPE_AFFINE_BOX ? 4 : 0;
			localTypeTests[typeIndex]++;
		}
		found |= tracePrimitiveRef(ref, type, spheres, boxes,
				affineSpheres, affineBoxes,
				sphereCount, boxCount, affineSphereCount,
				materials, origin, dir, hit);
	}
	return found;
}
""".concat("""

__device__ inline bool traceBvh(const float *nodeBounds, int nodeCount,
		const int *nodeData, const int *primitiveRefs, int primitiveRefCount,
		const int *rootIndices, int rootCount,
		const float *spheres, const float *boxes,
		const float *affineSpheres, const float *affineBoxes,
		int sphereCount, int boxCount, int affineSphereCount,
		const float *materials, float3 origin, float3 dir, HitInfo *hit,
		unsigned long long *localAabbTests, unsigned long long *localPrimitiveTests,
		unsigned long long *localTypeTests, unsigned long long *localStackOverflows,
		unsigned long long *localMaxStackSize, unsigned long long *localInternalNodes,
		unsigned long long *localLeafNodes, unsigned long long *localHomogeneousLeafNodes,
		unsigned long long *localMixedLeafNodes) {
	if (nodeCount <= 0 || primitiveRefCount <= 0
			|| rootCount <= 0 || rootCount > 4) return false;
	int nodeStack[BVH_STACK_SIZE];
	float entryStack[BVH_STACK_SIZE];
	int stackSize = 0;
	bool found = false;
	bool overflow = false;
	// Insert roots from farthest to nearest. The nearest root is popped first;
	// equal entry distances retain primitive-type order and deterministic ties.
	for (int root = 0; root < rootCount; root++) {
		int rootIndex = rootIndices[root];
		if (rootIndex < 0 || rootIndex >= nodeCount) { overflow = true; break; }
		if (localAabbTests != nullptr) (*localAabbTests)++;
		float rootEntry = bvhEntryDistance(nodeBounds, rootIndex, origin, dir, hit->t);
		if (!isfinite(rootEntry)) continue;
		if (stackSize >= BVH_STACK_SIZE) { overflow = true; break; }
		int position = stackSize;
		while (position > 0 && rootEntry >= entryStack[position - 1]) {
			nodeStack[position] = nodeStack[position - 1];
			entryStack[position] = entryStack[position - 1];
			position--;
		}
		nodeStack[position] = rootIndex;
		entryStack[position] = rootEntry;
		stackSize++;
		if (localMaxStackSize != nullptr
				&& (unsigned long long) stackSize > *localMaxStackSize) {
			*localMaxStackSize = (unsigned long long) stackSize;
		}
	}
	while (!overflow && stackSize > 0) {
		int nodeIndex = nodeStack[--stackSize];
		float entry = entryStack[stackSize];
		float conservativeHitT = isfinite(hit->t)
				? fmaf(hit->t, 1.0000015f, 1.0e-6f)
				: hit->t;
		if (entry > conservativeHitT) continue;
		int offset = nodeIndex * 4;
		int leftOrKind = nodeData[offset];
		int right = nodeData[offset + 1];
		int first = nodeData[offset + 2];
		int count = nodeData[offset + 3];
		if (leftOrKind < 0) {
			if (localLeafNodes != nullptr) (*localLeafNodes)++;
			if (leftOrKind == BVH_LEAF_KIND_MIXED) {
				if (localMixedLeafNodes != nullptr) (*localMixedLeafNodes)++;
			} else if (localHomogeneousLeafNodes != nullptr) {
				(*localHomogeneousLeafNodes)++;
			}
			found |= traceBvhLeaf(leftOrKind, first, count, primitiveRefs, primitiveRefCount,
					spheres, boxes, affineSpheres, affineBoxes,
					sphereCount, boxCount, affineSphereCount,
					materials, origin, dir, hit, localPrimitiveTests, localTypeTests);
			continue;
		}
		if (localInternalNodes != nullptr) (*localInternalNodes)++;
		float leftEntry = bvhEntryDistance(nodeBounds, leftOrKind, origin, dir, hit->t);
		float rightEntry = bvhEntryDistance(nodeBounds, right, origin, dir, hit->t);
		if (localAabbTests != nullptr) *localAabbTests += 2ULL;
		int nearNode = leftOrKind;
		int farNode = right;
		float nearEntry = leftEntry;
		float farEntry = rightEntry;
		if (rightEntry < leftEntry) {
			nearNode = right; farNode = leftOrKind;
			nearEntry = rightEntry; farEntry = leftEntry;
		}
		if (isfinite(farEntry)) {
			if (stackSize >= BVH_STACK_SIZE) { overflow = true; break; }
			nodeStack[stackSize] = farNode;
			entryStack[stackSize++] = farEntry;
			if (localMaxStackSize != nullptr
					&& (unsigned long long) stackSize > *localMaxStackSize) {
				*localMaxStackSize = (unsigned long long) stackSize;
			}
		}
		if (isfinite(nearEntry)) {
			if (stackSize >= BVH_STACK_SIZE) { overflow = true; break; }
			nodeStack[stackSize] = nearNode;
			entryStack[stackSize++] = nearEntry;
			if (localMaxStackSize != nullptr
					&& (unsigned long long) stackSize > *localMaxStackSize) {
				*localMaxStackSize = (unsigned long long) stackSize;
			}
		}
	}
	if (overflow) {
		if (localStackOverflows != nullptr) (*localStackOverflows)++;
		for (int i = 0; i < primitiveRefCount; i++) {
			if (localPrimitiveTests != nullptr) (*localPrimitiveTests)++;
			if (localTypeTests != nullptr) {
				int type = primitiveRefs[i] & REF_TYPE_MASK;
				int typeIndex = type == REF_TYPE_BOX ? 1
						: type == REF_TYPE_AFFINE_SPHERE ? 3
						: type == REF_TYPE_AFFINE_BOX ? 4 : 0;
				localTypeTests[typeIndex]++;
			}
			found |= tracePrimitiveRef(primitiveRefs[i],
					primitiveRefs[i] & REF_TYPE_MASK, spheres, boxes,
					affineSpheres, affineBoxes,
					sphereCount, boxCount, affineSphereCount,
					materials, origin, dir, hit);
		}
	}
	return found;
}

__device__ inline bool traceClosest(const float *spheres, int sphereCount,
                                    const float *boxes, int boxCount,
                                    const float *planes, int planeCount,
                                    const float *affineSpheres, int affineSphereCount,
                                    const float *affineBoxes, int affineBoxCount,
                                    const float *bvhNodeBounds, int bvhNodeCount,
                                    const int *bvhNodeData, const int *bvhPrimitiveRefs, int bvhPrimitiveRefCount,
									const int *bvhRootIndices, int bvhRootCount,
                                    int useBvh,
                                    const float *materials,
					float3 origin, float3 dir, HitInfo *hit,
					unsigned long long *localAabbTests, unsigned long long *localPrimitiveTests,
					unsigned long long *localTypeTests, unsigned long long *localStackOverflows,
					unsigned long long *localMaxStackSize, unsigned long long *localInternalNodes,
					unsigned long long *localLeafNodes,
					unsigned long long *localHomogeneousLeafNodes,
					unsigned long long *localMixedLeafNodes) {
	hit->hit = 0;
	hit->t = 1e30f;
	hit->primitiveOrder = 0x7FFFFFFF;
	bool found = false;
	if (useBvh != 0 && bvhNodeCount > 0) {
		found = traceBvh(bvhNodeBounds, bvhNodeCount, bvhNodeData, bvhPrimitiveRefs, bvhPrimitiveRefCount,
				bvhRootIndices, bvhRootCount,
				spheres, boxes, affineSpheres, affineBoxes,
				sphereCount, boxCount, affineSphereCount,
				materials, origin, dir, hit, localAabbTests, localPrimitiveTests, localTypeTests,
				localStackOverflows, localMaxStackSize, localInternalNodes, localLeafNodes,
				localHomogeneousLeafNodes, localMixedLeafNodes);
	} else {
		if (localPrimitiveTests != nullptr) {
			*localPrimitiveTests += (unsigned long long) sphereCount + boxCount
					+ affineSphereCount + affineBoxCount;
		}
		if (localTypeTests != nullptr) {
			localTypeTests[0] += sphereCount;
			localTypeTests[1] += boxCount;
			localTypeTests[3] += affineSphereCount;
			localTypeTests[4] += affineBoxCount;
		}
		int boxOrderBase = sphereCount;
		int affineSphereOrderBase = boxOrderBase + boxCount;
		int affineBoxOrderBase = affineSphereOrderBase + affineSphereCount;
		found = traceSphere(spheres, sphereCount, materials, origin, dir, 0, hit);
		found |= traceBox(boxes, boxCount, materials, origin, dir, boxOrderBase, hit);
		found |= traceAffineSphere(
				affineSpheres, affineSphereCount, materials, origin, dir,
				affineSphereOrderBase, hit);
		found |= traceAffineBox(
				affineBoxes, affineBoxCount, materials, origin, dir,
				affineBoxOrderBase, hit);
	}
	if (localPrimitiveTests != nullptr) *localPrimitiveTests += (unsigned long long) planeCount;
	if (localTypeTests != nullptr) localTypeTests[2] += planeCount;
	int planeOrderBase = sphereCount + boxCount + affineSphereCount + affineBoxCount;
	found |= tracePlane(planes, planeCount, materials, origin, dir, planeOrderBase, hit);
	return found;
}

__device__ inline float3 reflect_dir(float3 normal, float3 incoming) {
	return normal * (2.0f * dot(incoming, normal)) - incoming;
}

__device__ inline bool refract_dir(float3 incoming, float3 normal, float eta, float3 *result) {
	float3 i = normalizeSafe(incoming);
	float c1 = dot(i, normal);
	float ri = (c1 >= 0.0f) ? eta : -1.0f / eta;
	float invRi2 = 1.0f / (ri * ri);
	float c2Sqr = 1.0f - (1.0f - c1 * c1) * invRi2;
	if (c2Sqr <= 0.0f) {
		*result = normalizeSafe(reflect_dir(normal, i));
		return false;
	}
	float sqrtC2 = sqrtf(c2Sqr);
	*result = normalizeSafe(normal * (c1 - sqrtC2 * ri) - i);
	return true;
}

__device__ inline void buildOrthonormalBasis(float3 n, float3 *tangent, float3 *bitangent) {
	if (fabsf(n.z) < 0.999f) {
		*tangent = normalizeSafe(make_vec(-n.y, n.x, 0.0f));
	} else {
		*tangent = normalizeSafe(make_vec(0.0f, 1.0f, 0.0f));
	}
	*bitangent = cross(n, *tangent);
}

__device__ inline float3 sampleUnitSphere(Rng *rng) {
	float z = rand01(rng) * 2.0f - 1.0f;
	float t = rand01(rng) * 2.0f * CUDART_PI_F;
	float r = sqrtf(fmaxf(0.0f, 1.0f - z * z));
	return make_vec(r * cosf(t), r * sinf(t), z);
}

__device__ inline float3 diffuseSampleCpuStyle(Rng *rng, float3 normal) {
	return sampleUnitSphere(rng) + normal;
}

__device__ float3 tracePath(float3 origin,
                            float3 dir,
                            const float *spheres,
                            int sphereCount,
                            const float *boxes,
                            int boxCount,
                            const float *planes,
                            int planeCount,
                            const float *affineSpheres,
                            int affineSphereCount,
                            const float *affineBoxes,
                            int affineBoxCount,
                            const float *bvhNodeBounds,
                            int bvhNodeCount,
                            const int *bvhNodeData,
                            const int *bvhPrimitiveRefs,
                            int bvhPrimitiveRefCount,
							const int *bvhRootIndices,
							int bvhRootCount,
                            int useBvh,
                            const float *materials,
                            float3 background,
                            int maxDepth,
							Rng *rng,
							unsigned long long *localRays,
							unsigned long long *localAabbTests,
							unsigned long long *localPrimitiveTests,
							unsigned long long *localTypeTests,
							unsigned long long *localStackOverflows,
							unsigned long long *localMaxStackSize,
							unsigned long long *localInternalNodes,
							unsigned long long *localLeafNodes,
							unsigned long long *localHomogeneousLeafNodes,
							unsigned long long *localMixedLeafNodes) {
	float3 throughput = make_vec(1.0f, 1.0f, 1.0f);
	float3 radiance = make_vec(0.0f, 0.0f, 0.0f);

	for (int depth = 0; depth < maxDepth; ++depth) {
		if (localRays != nullptr) (*localRays)++;
		HitInfo hit{};
		bool hitFound = traceClosest(
				spheres, sphereCount,
				boxes, boxCount,
				planes, planeCount,
				affineSpheres, affineSphereCount,
				affineBoxes, affineBoxCount,
				bvhNodeBounds, bvhNodeCount, bvhNodeData, bvhPrimitiveRefs, bvhPrimitiveRefCount,
				bvhRootIndices, bvhRootCount, useBvh,
				materials,
				origin, dir, &hit, localAabbTests, localPrimitiveTests, localTypeTests,
				localStackOverflows, localMaxStackSize, localInternalNodes, localLeafNodes,
				localHomogeneousLeafNodes, localMixedLeafNodes
		);

		if (!hitFound) {
			radiance += throughput * background;
			break;
		}

		radiance += throughput * hit.material.emittance;

		float3 normal = hit.normal;
		float3 incoming = -dir;

		float3 cDiffuse = hit.material.diffuse;
		float3 cReflective = hit.material.reflective;
		float3 cRefractive = hit.material.refractive;

		float wDiffuse = luminance(cDiffuse);
		float wReflective = luminance(cReflective);
		float wRefractive = luminance(cRefractive);
		float sumWeights = wDiffuse + wReflective + wRefractive;

		if (sumWeights <= 0.0f) {
			break;
		}

		float xi = rand01(rng) * sumWeights;
		float3 newDir;
		float3 weightColor;
		float pComponent;

		if (xi < wDiffuse) {
			pComponent = wDiffuse / sumWeights;
			newDir = diffuseSampleCpuStyle(rng, normal);
			weightColor = cDiffuse / pComponent;
		} else if (xi < wDiffuse + wReflective) {
			pComponent = wReflective / sumWeights;
			newDir = reflect_dir(normal, incoming);
			weightColor = cReflective / pComponent;
		} else {
			pComponent = wRefractive / sumWeights;
			float3 refrDir;
			refract_dir(incoming, normal, hit.material.refractiveIndex, &refrDir);
			newDir = refrDir;
			weightColor = cRefractive / pComponent;
		}

		origin = hit.position + newDir * EPSILON;
		throughput = throughput * weightColor;
		dir = newDir;
	}

	return radiance;
}

extern "C"
__global__ void renderKernel(float *output,
                             int width,
                             int height,
							 int tileOffsetX,
							 int tileOffsetY,
							 int tileWidth,
							 int tileHeight,
                             float camPosX,
                             float camPosY,
                             float camPosZ,
                             float camForwardX,
                             float camForwardY,
                             float camForwardZ,
                             float camRightX,
                             float camRightY,
                             float camRightZ,
                             float camUpX,
                             float camUpY,
                             float camUpZ,
                             const float *spheres,
                             int sphereCount,
                             const float *boxes,
                             int boxCount,
                             const float *planes,
                             int planeCount,
                             const float *affineSpheres,
                             int affineSphereCount,
                             const float *affineBoxes,
                             int affineBoxCount,
                             const float *bvhNodeBounds,
                             int bvhNodeCount,
                             const int *bvhNodeData,
                             const int *bvhPrimitiveRefs,
                             int bvhPrimitiveRefCount,
							 const int *bvhRootIndices,
							 int bvhRootCount,
                             int useBvh,
                             const float *materials,
                             int materialCount,
                             float bgR,
                             float bgG,
                             float bgB,
                             int maxDepth,
                             int frameIndex,
                             unsigned long long frameSeed,
							 int sampleOffset,
							 int totalSamples,
							 unsigned long long *frameStats,
							 int collectMetrics) {
	int tileX = blockIdx.x * blockDim.x + threadIdx.x;
	int tileY = blockIdx.y * blockDim.y + threadIdx.y;
	if (tileX >= tileWidth || tileY >= tileHeight) {
		return;
	}
	int x = tileOffsetX + tileX;
	int y = tileOffsetY + tileY;

	float3 camPos = make_vec(camPosX, camPosY, camPosZ);
	float3 camForward = make_vec(camForwardX, camForwardY, camForwardZ);
	float3 camRight = make_vec(camRightX, camRightY, camRightZ);
	float3 camUp = make_vec(camUpX, camUpY, camUpZ);
	float3 background = make_vec(bgR, bgG, bgB);

	unsigned int baseSeed = hash_u32((unsigned int)(frameSeed & 0xFFFFFFFFULL));
	baseSeed ^= hash_u32((unsigned int)(frameSeed >> 32));
	baseSeed ^= hash_u32((unsigned int) frameIndex);
	unsigned int pixelIndex = (unsigned int) y * (unsigned int) width + (unsigned int) x;
	baseSeed ^= hash_u32(pixelIndex);
	baseSeed = hash_u32(baseSeed);

#if GFXLAB_RENDER_METRICS
	unsigned long long localRays = 0ULL;
	unsigned long long localAabbTests = 0ULL;
	unsigned long long localPrimitiveTests = 0ULL;
	unsigned long long localTypeTests[5] = {0ULL, 0ULL, 0ULL, 0ULL, 0ULL};
	unsigned long long localStackOverflows = 0ULL;
	unsigned long long localMaxStackSize = 0ULL;
	unsigned long long localInternalNodes = 0ULL;
	unsigned long long localLeafNodes = 0ULL;
	unsigned long long localHomogeneousLeafNodes = 0ULL;
	unsigned long long localMixedLeafNodes = 0ULL;
#endif

	unsigned int seed = baseSeed ^ hash_u32((unsigned int) sampleOffset);
	seed ^= hash_u32((unsigned int) (sampleOffset * 0x9E3779B9u));
	seed = hash_u32(seed);
	Rng rng{seed};

	float jitterX = rand01(&rng) - 0.5f;
	float jitterY = rand01(&rng) - 0.5f;

	float aspect = (float) width / (float) height;
	float u = ((((float) x + 0.5f) + jitterX) * (2.0f / (float) width) - 1.0f) * aspect;
	float v = 1.0f - ((((float) y + 0.5f) + jitterY) * (2.0f / (float) height));

	float3 origin = camPos;
	float3 dir = camForward + camRight * u + camUp * v;
	float3 color = tracePath(origin, dir,
			spheres, sphereCount,
			boxes, boxCount,
			planes, planeCount,
			affineSpheres, affineSphereCount,
			affineBoxes, affineBoxCount,
			bvhNodeBounds, bvhNodeCount, bvhNodeData, bvhPrimitiveRefs, bvhPrimitiveRefCount,
			bvhRootIndices, bvhRootCount, useBvh,
			materials,
			background, maxDepth, &rng,
#if GFXLAB_RENDER_METRICS
			&localRays, &localAabbTests, &localPrimitiveTests, localTypeTests,
			&localStackOverflows, &localMaxStackSize, &localInternalNodes, &localLeafNodes,
			&localHomogeneousLeafNodes, &localMixedLeafNodes);
#else
			nullptr, nullptr, nullptr, nullptr,
			nullptr, nullptr, nullptr, nullptr, nullptr, nullptr);
#endif

	int idx = (int) pixelIndex * 3;
	float3 accumulated = sampleOffset == 0
			? color
			: make_vec(output[idx + 0], output[idx + 1], output[idx + 2]) + color;
	if (sampleOffset == totalSamples - 1) {
		accumulated = accumulated * (1.0f / (float) totalSamples);
	}
	output[idx + 0] = accumulated.x;
	output[idx + 1] = accumulated.y;
	output[idx + 2] = accumulated.z;
#if GFXLAB_RENDER_METRICS
	if (frameStats != nullptr) {
		atomicAdd(&frameStats[STAT_RAYS], localRays);
		atomicAdd(&frameStats[STAT_AABB_TESTS], localAabbTests);
		atomicAdd(&frameStats[STAT_PRIMITIVE_TESTS], localPrimitiveTests);
		atomicAdd(&frameStats[STAT_SPHERE_TESTS], localTypeTests[0]);
		atomicAdd(&frameStats[STAT_BOX_TESTS], localTypeTests[1]);
		atomicAdd(&frameStats[STAT_PLANE_TESTS], localTypeTests[2]);
		atomicAdd(&frameStats[STAT_AFFINE_SPHERE_TESTS], localTypeTests[3]);
		atomicAdd(&frameStats[STAT_AFFINE_BOX_TESTS], localTypeTests[4]);
		atomicAdd(&frameStats[STAT_STACK_OVERFLOWS], localStackOverflows);
		atomicMax(&frameStats[STAT_MAX_STACK_SIZE], localMaxStackSize);
		atomicAdd(&frameStats[STAT_INTERNAL_NODES], localInternalNodes);
		atomicAdd(&frameStats[STAT_LEAF_NODES], localLeafNodes);
		atomicAdd(&frameStats[STAT_HOMOGENEOUS_LEAF_NODES], localHomogeneousLeafNodes);
		atomicAdd(&frameStats[STAT_MIXED_LEAF_NODES], localMixedLeafNodes);
	}
#else
	(void) collectMetrics;
	(void) frameStats;
#endif
}

extern "C"
__global__ void traceReplayKernel(const float *rays,
                                  int rayCount,
                                  const float *spheres, int sphereCount,
                                  const float *boxes, int boxCount,
                                  const float *planes, int planeCount,
                                  const float *affineSpheres, int affineSphereCount,
                                  const float *affineBoxes, int affineBoxCount,
                                  const float *bvhNodeBounds, int bvhNodeCount,
                                  const int *bvhNodeData,
                                  const int *bvhPrimitiveRefs, int bvhPrimitiveRefCount,
								  const int *bvhRootIndices, int bvhRootCount,
                                  int useBvh,
                                  const float *materials,
                                  int *hitFlags,
                                  float *hitDistances,
                                  int *hitPrimitiveOrders,
                                  int *hitMaterialIndices,
                                  float *hitNormals,
                                  int collectMetrics,
                                  unsigned long long *frameStats) {
	int rayIndex = blockIdx.x * blockDim.x + threadIdx.x;
	if (rayIndex >= rayCount) return;

	int offset = rayIndex * 6;
	float3 origin = make_vec(rays[offset], rays[offset + 1], rays[offset + 2]);
	float3 direction = make_vec(rays[offset + 3], rays[offset + 4], rays[offset + 5]);
	unsigned long long aabbTests = 0ULL;
	unsigned long long primitiveTests = 0ULL;
	unsigned long long typeTests[5] = {0ULL, 0ULL, 0ULL, 0ULL, 0ULL};
	unsigned long long stackOverflows = 0ULL;
	unsigned long long maxStackSize = 0ULL;
	unsigned long long internalNodes = 0ULL;
	unsigned long long leafNodes = 0ULL;
	unsigned long long homogeneousLeafNodes = 0ULL;
	unsigned long long mixedLeafNodes = 0ULL;
	HitInfo hit{};
	bool found = traceClosest(
			spheres, sphereCount, boxes, boxCount, planes, planeCount,
			affineSpheres, affineSphereCount, affineBoxes, affineBoxCount,
			bvhNodeBounds, bvhNodeCount, bvhNodeData, bvhPrimitiveRefs, bvhPrimitiveRefCount,
			bvhRootIndices, bvhRootCount, useBvh, materials, origin, direction, &hit,
			collectMetrics ? &aabbTests : nullptr,
			collectMetrics ? &primitiveTests : nullptr,
			collectMetrics ? typeTests : nullptr,
			collectMetrics ? &stackOverflows : nullptr,
			collectMetrics ? &maxStackSize : nullptr,
			collectMetrics ? &internalNodes : nullptr,
			collectMetrics ? &leafNodes : nullptr,
			collectMetrics ? &homogeneousLeafNodes : nullptr,
			collectMetrics ? &mixedLeafNodes : nullptr);

	hitFlags[rayIndex] = found ? 1 : 0;
	hitDistances[rayIndex] = found ? hit.t : CUDART_INF_F;
	hitPrimitiveOrders[rayIndex] = found ? hit.primitiveOrder : -1;
	hitMaterialIndices[rayIndex] = found ? hit.materialIndex : -1;
	int normalOffset = rayIndex * 3;
	hitNormals[normalOffset] = found ? hit.normal.x : 0.0f;
	hitNormals[normalOffset + 1] = found ? hit.normal.y : 0.0f;
	hitNormals[normalOffset + 2] = found ? hit.normal.z : 0.0f;
	if (collectMetrics != 0) {
		atomicAdd(&frameStats[STAT_RAYS], 1ULL);
		atomicAdd(&frameStats[STAT_AABB_TESTS], aabbTests);
		atomicAdd(&frameStats[STAT_PRIMITIVE_TESTS], primitiveTests);
		atomicAdd(&frameStats[STAT_SPHERE_TESTS], typeTests[0]);
		atomicAdd(&frameStats[STAT_BOX_TESTS], typeTests[1]);
		atomicAdd(&frameStats[STAT_PLANE_TESTS], typeTests[2]);
		atomicAdd(&frameStats[STAT_AFFINE_SPHERE_TESTS], typeTests[3]);
		atomicAdd(&frameStats[STAT_AFFINE_BOX_TESTS], typeTests[4]);
		atomicAdd(&frameStats[STAT_STACK_OVERFLOWS], stackOverflows);
		atomicMax(&frameStats[STAT_MAX_STACK_SIZE], maxStackSize);
		atomicAdd(&frameStats[STAT_INTERNAL_NODES], internalNodes);
		atomicAdd(&frameStats[STAT_LEAF_NODES], leafNodes);
		atomicAdd(&frameStats[STAT_HOMOGENEOUS_LEAF_NODES], homogeneousLeafNodes);
		atomicAdd(&frameStats[STAT_MIXED_LEAF_NODES], mixedLeafNodes);
	}
}
""");
}
