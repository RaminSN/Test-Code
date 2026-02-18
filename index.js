import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import * as R from 'ramda';
import crypto from 'crypto';
import { exit } from 'process';

const inputDir = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'input',
);
const outputDir = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'output',
);

const t5ServerTimestampRegex = /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3})/;

//#region Helpers
const shallowStructureKey = (obj) =>
  `reportType_${simpleHash(JSON.stringify(Object.keys(obj).sort())).substring(0, 8)}`;

const stableHash = (value) => {
  if (value === null) return 'null';

  if (typeof value !== 'object') {
    return `${typeof value}:${String(value)}`;
  }

  if (Array.isArray(value)) {
    return `array:[${value.map(stableHash).join(',')}]`;
  }

  // object
  const keys = Object.keys(value).sort();
  return `object:{${keys
    .map((k) => `${k}:${stableHash(value[k])}`)
    .join('|')}}`;
};

const simpleHash = (str) => crypto.createHash('md5').update(str).digest('hex');

// helper: consolidate instead of printing the same payload N times
const collapseGroup = (group) => {
  const t5ServerTimestamps = R.map(R.prop('t5ServerTimestamp'))(group);
  const { t5ServerTimestamp, hash, ...rest } = group[0];
  return {
    payload: rest,
    count: group.length,
    t5ServerTimestamps,
  };
};

// helper: filter hash groups where count >= 2
const filterHashGroups = (hashGroupObj) =>
  Object.fromEntries(
    Object.entries(hashGroupObj)
      .map(([hash, group]) => [hash, collapseGroup(group)])
      .filter(([_, collapsed]) => collapsed && collapsed.count >= 2),
  );
//#endregion

const isValidLogFile = (monthName) => (filename) =>
  filename.includes(`ALLT_${monthName}`);

const readFile = (dirPath) => (filename) =>
  fs.readFileSync(path.join(dirPath, filename), 'utf8');

const readErrorFiles = (dirPath, monthName) =>
  R.pipe(
    fs.readdirSync,
    R.filter(isValidLogFile(monthName)),
    R.map(readFile(dirPath)),
  )(dirPath);

const splitByEntry = (text) =>
  text.split(new RegExp(`(?=${t5ServerTimestampRegex.source})`, 'm'));

const isNVVJsonEntry = (text) =>
  text.includes('Info') &&
  text.includes('rdsverket - Bot') &&
  text.includes('{');

const processTextIntoEntries = R.pipe(splitByEntry, R.filter(isNVVJsonEntry));

const parseJSON = (text) => {
  const match = text.match(t5ServerTimestampRegex);
  const t5ServerTimestamp = match[1];
  const jsonStart = text.indexOf('{');
  const parsedJson = JSON.parse(text.slice(jsonStart));
  const hash = `report_${simpleHash(stableHash(parsedJson))}`;

  return { t5ServerTimestamp, hash, ...parsedJson };
};

const monthNames = [
  '2024-07',
  '2025-09',
  '2025-10',
  '2025-11',
  '2025-12',
  '2026-01',
  '2026-02',
];

if (!fs.existsSync(inputDir)) {
  console.error(`Input directory does not exist: ${inputDir}`);
  exit();
}

if (!fs.statSync(inputDir).isDirectory()) {
  console.error(`Path is not a directory: ${inputDir}`);
  exit();
}

fs.mkdirSync(outputDir, { recursive: true });
console.log('Created output folder.\n');

for (let monthName of monthNames) {
  console.log(`Processing logs for ${monthName}...`);
  const nvvReportsByTypeAndHash = R.pipe(
    R.map(processTextIntoEntries),
    R.flatten,
    R.map(parseJSON),
    R.groupBy(shallowStructureKey),
    R.map(R.groupBy(R.prop('hash'))),
    R.map(filterHashGroups),
  )(readErrorFiles(inputDir, monthName));

  const outputPath = path.join(
    outputDir,
    `duplicated-nvv-reports_${monthName}.json`,
  );
  fs.writeFileSync(outputPath, JSON.stringify(nvvReportsByTypeAndHash), 'utf8');
  console.log(`Saved file: ${outputPath}\n`);
}
console.log('Finished.');
