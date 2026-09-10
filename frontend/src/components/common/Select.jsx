import './Select.css';

export function Select({ label, value, onChange, options }) {
  return (
    <label className="qs-select">
      <span className="qs-select__label">{label}</span>
      <select
        className="qs-select__control"
        name={label.toLowerCase().replace(/\s+/g, '-')}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value}>
            {opt.label}
          </option>
        ))}
      </select>
    </label>
  );
}
