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

const orgDir = path.join(outputDir, `org_files`);

const t5ServerTimestampRegex = /(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3})/;

//#region Helpers
const hashReportType = (report) =>
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

const filterDuplicatedReports = R.pipe(
  R.toPairs,
  R.map(R.adjust(1, collapseGroup)),
  R.filter(R.pipe(R.last, R.prop('count'), R.gte(R.__, 2))),
  R.fromPairs,
);

// checks if an object has a certain value in any of the provided props
const hasAnyPropWithValue = (props, value, obj) =>
  R.any(R.pipe(R.flip(R.prop)(obj), R.equals(value)), props);
//#endregion

const isValidLogFile = (monthName) => (filename) =>
  filename.includes(`ALLT_${monthName}`);

const readFile = (dirPath) => (filename) =>
  fs.readFileSync(path.join(dirPath, filename), 'latin1');

const getFileContents = (dirPath) => (monthName) =>
  R.pipe(
    fs.readdirSync,
    R.filter(isValidLogFile(monthName)),
    R.map(readFile(dirPath)),
  )(dirPath);

const getLogEntries = (text) =>
  text.split(new RegExp(`(?=${t5ServerTimestampRegex.source})`, 'm'));

const isRequest = (text) =>
  text.includes('Info') &&
  text.includes('Naturvårdsverket - Bot') &&
  text.includes('{');

const isErrorResponse = (text) =>
  text.includes('Info') &&
  text.includes('Naturvårdsverket - Bot') &&
  text.includes('TraceId');

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

const getReportsForOrgnr = (orgNumber) =>
  R.pipe(
    getFlatReports,
    R.filter(R.propSatisfies(isPayloadRelatedTo(orgNumber), 'payload')),
  );

const filterSuccessfulRequests = R.pipe(
  R.filter(R.either(isRequest, isErrorResponse)),
  R.aperture(2),
  R.filter(
    R.allPass([
      R.pipe(R.head, R.complement(isErrorResponse)),
      R.pipe(R.last, isRequest),
    ]),
  ),
  R.pluck(1),
);

console.log('Resetting output folder.');
fs.rmSync(outputDir, { recursive: true, force: true });
fs.mkdirSync(outputDir, { recursive: true });

console.log('Resetting org folder.');
fs.rmSync(orgDir, { recursive: true, force: true });
fs.mkdirSync(orgDir, { recursive: true });

console.log();

const groupedReports = R.pipe(
  R.tap(() => console.log('Step 1/5: Reading files.')),
  R.chain(getFileContents(inputDir)),

  R.tap(() => console.log('Step 2/5: Parsing logs.')),
  R.chain(R.pipe(getLogEntries, filterSuccessfulRequests)),
  R.map(parseLogEntry),

  R.tap(() => console.log('Step 3/5: Consolidating reports.')),
  R.groupBy(hashReportType),
  R.map(R.pipe(R.groupBy(R.prop('hash')), filterDuplicatedReports)),
)(months);

console.log(`Highest count: ${getHighestCount(groupedReports).count}`);
console.log(`Total count: ${getCountSum(groupedReports)}`);

const outputPath = path.join(outputDir, `duplicated-nvv-reports.json`);

console.log(`Step 4/5: Creating file ${outputPath}.`);
fs.writeFileSync(outputPath, JSON.stringify(groupedReports, null, 2), 'utf8');

console.log(`Step 5/5: Creating org files.`);

for (let orgNumber of orgNumbers) {
  let reports = getReportsForOrgnr(orgNumber)(groupedReports);
  if (reports.length == 0) continue;
  let specificOrgDir = path.join(orgDir, orgNumber);
  const orgPath = path.join(specificOrgDir, `duplicated-nvv-reports.json`);

  fs.mkdirSync(specificOrgDir, { recursive: true });
  fs.writeFileSync(orgPath, JSON.stringify(reports, null, 2), 'utf-8');
}

console.log(`Finished!`);
