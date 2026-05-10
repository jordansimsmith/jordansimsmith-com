import { splitMorae } from '../domain/morae';

export type PitchPattern = 'heiban' | 'atamadaka' | 'nakadaka' | 'odaka';

export function getPitchPattern(reading: string, pitch: number): PitchPattern {
  const moraCount = splitMorae(reading).length;
  if (pitch === 0) {
    return 'heiban';
  }
  if (pitch === 1) {
    return 'atamadaka';
  }
  if (pitch >= moraCount) {
    return 'odaka';
  }
  return 'nakadaka';
}

function isMoraPitchHigh(moraIndex: number, pitch: number): boolean {
  if (pitch === 0) {
    return moraIndex !== 0;
  }
  if (pitch === 1) {
    return moraIndex === 0;
  }
  return moraIndex !== 0 && moraIndex < pitch;
}

interface PitchGraphProps {
  reading: string;
  pitch: number;
}

const STEP = 50;
const RADIUS = 15;
const HIGH_Y = 25;
const LOW_Y = 75;

export function PitchGraph({ reading, pitch }: PitchGraphProps) {
  const morae = splitMorae(reading);
  const moraCount = morae.length;
  if (moraCount === 0) {
    return null;
  }

  const pattern = getPitchPattern(reading, pitch);
  const width = STEP * (moraCount + 1);

  const points: { x: number; y: number; high: boolean }[] = [];
  for (let i = 0; i < moraCount; i++) {
    const x = i * STEP + STEP / 2;
    const high = isMoraPitchHigh(i, pitch);
    points.push({ x, y: high ? HIGH_Y : LOW_Y, high });
  }
  const particleX = moraCount * STEP + STEP / 2;
  const particleHigh = isMoraPitchHigh(moraCount, pitch);
  const particleY = particleHigh ? HIGH_Y : LOW_Y;

  const linePath = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x} ${p.y}`)
    .join(' ');
  const tailPath = `M${points[points.length - 1].x} ${points[points.length - 1].y} L${particleX} ${particleY}`;

  return (
    <svg
      role="img"
      aria-label={`Pitch pattern: ${pattern}`}
      data-pitch-pattern={pattern}
      xmlns="http://www.w3.org/2000/svg"
      viewBox={`0 0 ${width} 100`}
      style={{ display: 'block', maxWidth: width, height: 50 }}
    >
      <path
        className="pitch-graph-line"
        d={linePath}
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
      />
      <path
        className="pitch-graph-line-tail"
        d={tailPath}
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeDasharray="4 4"
      />
      {points.map((p, i) => {
        const next =
          i + 1 < points.length ? points[i + 1] : { high: particleHigh };
        const isDownstep = p.high && !next.high;
        return (
          <g key={i} data-mora-index={i} data-pitch={p.high ? 'high' : 'low'}>
            <circle
              className="pitch-graph-dot"
              cx={p.x}
              cy={p.y}
              r={RADIUS}
              fill="currentColor"
            />
            {isDownstep && (
              <circle
                className="pitch-graph-dot-downstep"
                cx={p.x}
                cy={p.y}
                r={RADIUS / 3}
                fill="white"
              />
            )}
          </g>
        );
      })}
      <path
        className="pitch-graph-particle"
        d={`M${particleX - RADIUS} ${particleY + RADIUS} L${particleX + RADIUS} ${particleY + RADIUS} L${particleX} ${particleY - RADIUS} Z`}
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        data-particle-pitch={particleHigh ? 'high' : 'low'}
      />
    </svg>
  );
}
