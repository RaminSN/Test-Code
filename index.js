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

const monthsFile = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'months.json',
);

const orgNumbersFile = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'org-numbers.json',
);

const orgDir = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  `org_files`,
);

const t5ServerTimestampRegex = /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3})/;

//#region Helpers
const shallowStructureKey = (report) =>
  `reportType_${simpleHash(JSON.stringify(Object.keys(report).sort())).substring(0, 8)}`;

// Converts an object to a string regardless of internal entry order. Can be safely used as a fingerprint whereas JSON.stringify can't.
const stableStringify = (value) => {
  if (value === null) return 'null';

  if (typeof value !== 'object') {
    return `${typeof value}:${String(value)}`;
  }

  if (Array.isArray(value)) {
    return `array:[${value.map(stableStringify).join(',')}]`;
  }

  const keys = Object.keys(value).sort();
  return `object:{${keys
    .map((k) => `${k}:${stableStringify(value[k])}`)
    .join('|')}}`;
};

const simpleHash = (str) => crypto.createHash('md5').update(str).digest('hex');

// consolidate instead of including the same payload multiple times
const collapseGroup = (group) => {
  const t5ServerTimestamps = R.map(R.prop('t5ServerTimestamp'))(group);
  const { t5ServerTimestamp, ...rest } = group[0];
  return {
    payload: rest,
    count: group.length,
    t5ServerTimestamps,
  };
};

// filter hash groups where count >= 2
const filterHashGroups = (hashGroupObj) =>
  Object.fromEntries(
    Object.entries(hashGroupObj)
      .map(([hash, group]) => [hash, collapseGroup(group)])
      .filter(([_, collapsed]) => collapsed && collapsed.count >= 2),
  );

// checks if an object has a certain value in any of the provided props
const hasAnyPropWithValue = (props, value, obj) =>
  R.any(R.pipe(R.flip(R.prop)(obj), R.equals(value)), props);
//#endregion

const isValidLogFile = (monthName) => (filename) =>
  filename.includes(`ALLT_${monthName}`);

const readFile = (dirPath) => (filename) =>
  fs.readFileSync(path.join(dirPath, filename), 'latin1');

const getFileContents = (dirPath, monthName) =>
  R.pipe(
    fs.readdirSync,
    R.filter(isValidLogFile(monthName)),
    R.map(readFile(dirPath)),
  )(dirPath);

const splitByEntry = (text) =>
  text.split(new RegExp(`(?=${t5ServerTimestampRegex.source})`, 'm'));

const isNVVJsonEntry = (text) =>
  text.includes('Info') &&
  text.includes('Naturvårdsverket - Bot') &&
  text.includes('{');

const parseLogEntry = (text) => {
  const match = text.match(t5ServerTimestampRegex);
  const t5ServerTimestamp = match[1];
  const jsonStart = text.indexOf('{');
  const parsedJson = JSON.parse(text.slice(jsonStart));
  const hash = `report_${simpleHash(stableStringify(R.omit(['Tidpunkt'], parsedJson)))}`;

  return { t5ServerTimestamp, hash, ...parsedJson };
};

if (!fs.existsSync(monthsFile)) {
  console.error(`months.json does not exist: ${monthsFile}`);
  exit();
}

if (!fs.existsSync(orgNumbersFile)) {
  console.error(`org-numbers.json does not exist: ${orgNumbersFile}`);
  exit();
}

if (!fs.existsSync(inputDir)) {
  console.error(`Input directory does not exist: ${inputDir}`);
  exit();
}

if (!fs.statSync(inputDir).isDirectory()) {
  console.error(`Path is not a directory: ${inputDir}`);
  exit();
}

const months = JSON.parse(fs.readFileSync(monthsFile, 'utf8'));
const orgNumbers = JSON.parse(fs.readFileSync(orgNumbersFile, 'utf8'));

console.log('Resetting output folder.\n');
fs.rmSync(outputDir, { recursive: true, force: true });
fs.mkdirSync(outputDir, { recursive: true });

console.log('Resetting org folder.\n');
fs.rmSync(orgDir, { recursive: true, force: true });
fs.mkdirSync(orgDir, { recursive: true });

const getFlatReports = R.pipe(Object.values, R.chain(Object.values));

const getHighestCount = R.pipe(
  getFlatReports,
  R.sortBy(R.prop(['count'])),
  R.findLast(R.identity),
);

const getCountSum = R.pipe(getFlatReports, R.map(R.prop(['count'])), R.sum);

const isPayloadRelatedTo = (orgnr) => (payload) =>
  hasAnyPropWithValue(
    ['Ombud', 'VerksamhetsUtovare', 'TidigareInnehavare', 'Transportor'],
    orgnr,
    payload,
  );

const getReportsForOrgnr = (orgnr) =>
  R.pipe(
    getFlatReports,
    R.filter(R.pipe(R.prop(['payload']), isPayloadRelatedTo(orgnr))),
  );

for (let month of months) {
  console.log(`Processing logs for ${month}...`);
  const nvvReportsByTypeAndHash = R.pipe(
    R.chain(R.pipe(splitByEntry, R.filter(isNVVJsonEntry))),
    R.map(parseLogEntry),
    R.groupBy(shallowStructureKey),
    R.map(R.groupBy(R.prop('hash'))),
    R.map(filterHashGroups),
  )(getFileContents(inputDir, month));

  const outputPath = path.join(
    outputDir,
    `duplicated-nvv-reports_${month}.json`,
  );

  fs.writeFileSync(
    outputPath,
    JSON.stringify(nvvReportsByTypeAndHash, null, 2),
    'utf8',
  );

  console.log(
    `Highest count: ${getHighestCount(nvvReportsByTypeAndHash).count}`,
  );

  console.log(`Total count: ${getCountSum(nvvReportsByTypeAndHash)}`);

  console.log('Creating org files...\n');

  for (let orgNumber of orgNumbers) {
    let specificOrgDir = path.join(orgDir, orgNumber);
    const orgPath = path.join(specificOrgDir, `${month}.json`);

    fs.mkdirSync(specificOrgDir, { recursive: true });
    fs.writeFileSync(
      orgPath,
      JSON.stringify(
        getReportsForOrgnr(orgNumber)(nvvReportsByTypeAndHash),
        null,
        2,
      ),
      'utf-8',
    );
  }
}
console.log('Finished.');
