import { Autocomplete, Checkbox, TextField } from '@mui/material';
import type { Repository } from '../types/repositories';

export default function RepositorySelector({ repositories, selected, onChange, disabled }: {
  repositories: Repository[];
  selected: Repository[];
  onChange: (repositories: Repository[]) => void;
  disabled: boolean;
}) {
  return (
    <Autocomplete
      multiple
      disableCloseOnSelect
      limitTags={2}
      options={repositories}
      value={selected}
      disabled={disabled}
      onChange={(_, value) => onChange(value)}
      getOptionLabel={(repository) => repository.fullName}
      isOptionEqualToValue={(option, value) => option.id === value.id}
      renderOption={(props, option, { selected: checked }) => {
        const { key, ...optionProps } = props;
        return <li key={key} {...optionProps}>
          <Checkbox checked={checked} tabIndex={-1} sx={{ pointerEvents: 'none' }} />
          {option.fullName}
        </li>;
      }}
      renderInput={(params) => <TextField {...params} label="Select repositories" placeholder={selected.length ? undefined : 'All loaded repositories'} />}
      sx={{ width: { xs: '100%', md: 440 } }}
      size="small"
    />
  );
}
