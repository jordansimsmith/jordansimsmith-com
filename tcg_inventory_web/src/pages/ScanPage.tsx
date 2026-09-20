import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Badge,
  Button,
  FileInput,
  Group,
  Select,
  Stack,
  Table,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionLoadingState,
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { PageHeader } from '../components/PageHeader';
import { apiClient, CONDITIONS, FINISHES } from '../api/client';
import type { Condition, Finish, ScanStatus, ScanSummary } from '../api/client';
import { useListNavigation } from '../hooks/use-list-navigation';
import classes from '../components/CollectionTable.module.css';

const STATUS_COLORS: Record<ScanStatus, string> = {
  uploading: 'blue',
  identifying: 'blue',
  reviewing: 'orange',
  confirmed: 'green',
};

function formatStatus(status: ScanStatus): string {
  return status.replace('_', ' ');
}

function formatFinish(finish: ScanSummary['finish']): string {
  switch (finish) {
    case 'normal':
      return 'Normal';
    case 'foil':
      return 'Foil';
    case 'etched':
      return 'Etched';
  }
}

function ScanStatusBadge({ status }: { status: ScanStatus }) {
  return (
    <Badge variant="light" color={STATUS_COLORS[status]}>
      {formatStatus(status)}
    </Badge>
  );
}

interface ScanTableProps {
  scans: ScanSummary[];
  selectedIndex: number;
}

function ScanTable({ scans, selectedIndex }: ScanTableProps) {
  const selectedRowRef = useRef<HTMLTableRowElement>(null);

  useEffect(() => {
    selectedRowRef.current?.scrollIntoView({ block: 'nearest' });
  }, [selectedIndex]);

  return (
    <Table
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
      className={classes.table}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Created</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th ta="right">Cards</Table.Th>
          <Table.Th>Condition</Table.Th>
          <Table.Th>Finish</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {scans.map((scan, index) => {
          const selected = index === selectedIndex;
          return (
            <Table.Tr
              key={scan.scan_id}
              ref={selected ? selectedRowRef : undefined}
              data-selected={selected}
            >
              <Table.Td
                fw={500}
                data-field="created"
                data-label="Created"
                style={{ whiteSpace: 'nowrap' }}
              >
                {new Date(scan.created_at * 1000).toLocaleString()}
              </Table.Td>
              <Table.Td data-field="status" data-label="Status">
                <ScanStatusBadge status={scan.status} />
              </Table.Td>
              <Table.Td ta="right" data-field="cards" data-label="Cards">
                {scan.processed_count} / {scan.row_count}
              </Table.Td>
              <Table.Td data-field="condition" data-label="Condition">
                {scan.condition}
              </Table.Td>
              <Table.Td data-field="finish" data-label="Finish">
                {formatFinish(scan.finish)}
              </Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}

export function ScanPage() {
  const [scans, setScans] = useState<ScanSummary[]>([]);
  const [nextContinuation, setNextContinuation] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [condition, setCondition] = useState<Condition>('NM');
  const [finish, setFinish] = useState<Finish>('normal');
  const [files, setFiles] = useState<File[]>([]);
  const [creating, setCreating] = useState(false);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const openScan = useCallback(() => {}, []);
  const { selectedIndex } = useListNavigation({
    itemCount: scans.length,
    onOpen: openScan,
    searchInputRef,
  });

  useEffect(() => {
    let cancelled = false;
    const fetchScans = async () => {
      try {
        const response = await apiClient.findScans();
        if (!cancelled) {
          setScans(response.scans);
          setNextContinuation(response.next_continuation);
        }
      } catch (e) {
        if (!cancelled) {
          const message =
            e instanceof Error ? e.message : 'Failed to load scans';
          setError(message);
          notifications.show({ title: 'Error', message, color: 'red' });
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchScans();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleLoadMore = async () => {
    if (!nextContinuation) {
      return;
    }
    setLoadingMore(true);
    try {
      const response = await apiClient.findScans({
        continuation: nextContinuation,
      });
      setScans((previous) => [...previous, ...response.scans]);
      setNextContinuation(response.next_continuation);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to load more scans';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setLoadingMore(false);
    }
  };

  const handleCreate = async () => {
    if (files.length === 0) {
      return;
    }
    setCreating(true);
    try {
      const created = await apiClient.createScan({
        condition,
        finish,
        files: files.map((file) => ({
          filename: file.name,
          size_bytes: file.size,
        })),
      });
      setScans((previous) => [created, ...previous]);
      setFiles([]);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to create scan';
      notifications.show({
        title: 'Scan creation failed',
        message,
        color: 'red',
      });
    } finally {
      setCreating(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Scans"
          description="Upload ordered card-front JPEGs, verify every printing, then create an import."
        />
        <CollectionSurface
          ariaLabel="Scan jobs"
          toolbar={
            <Group align="flex-end" gap="sm" wrap="wrap">
              <Select
                label="Condition"
                value={condition}
                onChange={(value) => setCondition(value as Condition)}
                data={CONDITIONS}
                style={{ flex: '0 1 10rem' }}
              />
              <Select
                label="Finish"
                value={finish}
                onChange={(value) => setFinish(value as Finish)}
                data={FINISHES}
                style={{ flex: '0 1 10rem' }}
              />
              <FileInput
                value={files}
                onChange={setFiles}
                multiple
                accept=".jpg,.jpeg,image/jpeg"
                label="Scanner JPEGs"
                placeholder="Choose files"
                clearable
                style={{ flex: '1 1 16rem', maxWidth: 360 }}
              />
              <Button
                onClick={handleCreate}
                disabled={files.length === 0}
                loading={creating}
              >
                Create scan
              </Button>
            </Group>
          }
          footer={
            nextContinuation ? (
              <Group justify="flex-start">
                <Button
                  variant="default"
                  onClick={handleLoadMore}
                  loading={loadingMore}
                >
                  Load more
                </Button>
              </Group>
            ) : undefined
          }
        >
          {loading && <CollectionLoadingState rows={4} />}
          {!loading && error && (
            <CollectionMessage
              title="Scans could not be loaded"
              description={error}
              tone="error"
            />
          )}
          {!loading && !error && scans.length === 0 && (
            <CollectionMessage
              title="No scans yet."
              description="Set up a new scan above to start a scanner intake."
            />
          )}
          {!loading && !error && scans.length > 0 && (
            <ScanTable scans={scans} selectedIndex={selectedIndex} />
          )}
        </CollectionSurface>
      </Stack>
    </AppShellLayout>
  );
}
