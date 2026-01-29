import { Box, Text, useMantineTheme } from '@mantine/core';
import type { ChartPoint } from '../presenters/progress-presenter';

interface CumulativeHoursChartProps {
  points: ChartPoint[];
}

export function CumulativeHoursChart({ points }: CumulativeHoursChartProps) {
  const theme = useMantineTheme();

  if (points.length === 0) {
    return (
      <Text c="dimmed" ta="center">
        No progress data available
      </Text>
    );
  }

  const width = 600;
  const height = 200;
  const padding = { top: 20, right: 40, bottom: 40, left: 50 };
  const chartWidth = width - padding.left - padding.right;
  const chartHeight = height - padding.top - padding.bottom;

  const maxHours = Math.max(...points.map((p) => p.cumulativeHours), 1);
  const yScale = (hours: number) =>
    chartHeight - (hours / maxHours) * chartHeight;
  const xScale = (index: number) =>
    (index / Math.max(points.length - 1, 1)) * chartWidth;

  const pathData = points
    .map((point, i) => {
      const x = xScale(i);
      const y = yScale(point.cumulativeHours);
      return `${i === 0 ? 'M' : 'L'} ${x} ${y}`;
    })
    .join(' ');

  const areaData =
    pathData + ` L ${chartWidth} ${chartHeight} L 0 ${chartHeight} Z`;

  const primaryColor = theme.colors.indigo[6];
  const primaryColorLight = theme.colors.indigo[1];

  const yTicks = [0, Math.round(maxHours / 2), Math.round(maxHours)];
  const labelInterval = Math.max(1, Math.floor(points.length / 4));

  return (
    <Box style={{ width: '100%' }}>
      <svg
        viewBox={`0 0 ${width} ${height}`}
        style={{ width: '100%', height: 'auto' }}
        preserveAspectRatio="xMidYMid meet"
      >
        <g transform={`translate(${padding.left}, ${padding.top})`}>
          {yTicks.map((tick) => (
            <g key={tick}>
              <line
                x1={0}
                y1={yScale(tick)}
                x2={chartWidth}
                y2={yScale(tick)}
                stroke={theme.colors.gray[3]}
                strokeDasharray="4,4"
              />
              <text
                x={-8}
                y={yScale(tick)}
                textAnchor="end"
                dominantBaseline="middle"
                fontSize={12}
                fill={theme.colors.gray[6]}
              >
                {tick}h
              </text>
            </g>
          ))}

          <path d={areaData} fill={primaryColorLight} opacity={0.5} />
          <path
            d={pathData}
            fill="none"
            stroke={primaryColor}
            strokeWidth={2}
          />

          {points.map((point, i) => (
            <circle
              key={i}
              cx={xScale(i)}
              cy={yScale(point.cumulativeHours)}
              r={4}
              fill={primaryColor}
            />
          ))}

          {points.map((point, i) =>
            i % labelInterval === 0 || i === points.length - 1 ? (
              <text
                key={i}
                x={xScale(i)}
                y={chartHeight + 20}
                textAnchor="middle"
                fontSize={11}
                fill={theme.colors.gray[6]}
              >
                {point.label}
              </text>
            ) : null,
          )}
        </g>
      </svg>
    </Box>
  );
}
