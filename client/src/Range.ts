
export class Position {
  line: number;
  character: number;

  constructor(line: number, character: number) {
    this.line = line;
    this.character = character;
  }
}

export class Range {
  startPos: Position;
  endPos: Position;

  constructor(startPos: Position, endPos: Position) {
    this.startPos = startPos;
    this.endPos = endPos;
  }
}