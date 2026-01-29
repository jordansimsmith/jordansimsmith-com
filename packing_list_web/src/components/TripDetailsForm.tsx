import { TextInput, Stack } from '@mantine/core';
import { DatePickerInput } from '@mantine/dates';
import type { UseFormReturnType } from '@mantine/form';

export interface TripFormValues {
  name: string;
  destination: string;
  departure_date: Date | null;
  return_date: Date | null;
}

interface TripDetailsFormProps {
  form: UseFormReturnType<TripFormValues>;
  nameAutoFocus?: boolean;
}

export function TripDetailsForm({ form, nameAutoFocus }: TripDetailsFormProps) {
  return (
    <Stack gap="md">
      <TextInput
        label="Name"
        placeholder="Japan 2025"
        data-autofocus={nameAutoFocus || undefined}
        {...form.getInputProps('name')}
      />
      <TextInput
        label="Destination"
        placeholder="Tokyo"
        {...form.getInputProps('destination')}
      />
      <DatePickerInput
        label="Departure date"
        placeholder="Select date"
        valueFormat="DD MMM YYYY"
        {...form.getInputProps('departure_date')}
      />
      <DatePickerInput
        label="Return date"
        placeholder="Select date"
        valueFormat="DD MMM YYYY"
        {...form.getInputProps('return_date')}
      />
    </Stack>
  );
}
