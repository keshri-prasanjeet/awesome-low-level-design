package chessgame;

import chessgame.pieces.Piece;

public class ChessGameDemo {
    public static void run() {
        Board board = new Board();

        printPiece("Initial white piece", board.getPiece(1, 4));
        moveAndPrint(board, 1, 4, 3, 4);

        printPiece("Initial black piece", board.getPiece(6, 4));
        moveAndPrint(board, 6, 4, 4, 4);

        boolean invalidMove = board.movePiece(new Move(board.getCell(0, 0), board.getCell(2, 1)));
        System.out.println("Invalid rook move accepted? " + invalidMove);
    }

    private static void moveAndPrint(Board board, int fromRow, int fromCol, int toRow, int toCol) {
        Piece movingPiece = board.getPiece(fromRow, fromCol);
        boolean moved = board.movePiece(new Move(board.getCell(fromRow, fromCol), board.getCell(toRow, toCol)));
        System.out.println(movingPiece.getColor() + " " + movingPiece.getClass().getSimpleName()
                + " moved from (" + fromRow + ", " + fromCol + ") to (" + toRow + ", " + toCol + "): " + moved);
    }

    private static void printPiece(String label, Piece piece) {
        System.out.println(label + ": " + piece.getColor() + " " + piece.getClass().getSimpleName());
    }
}
