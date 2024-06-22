import csv
import pathlib
from typing import Iterator


def main() -> None:
    books = get_books("books.csv") 
    print(books)
    print(filter_books(books, "python"))
    print(get_books_amount(books))


def get_books(file_path: str) -> list[list[str]]:
    return list(_read_csv(file_path))[1:]


def _read_csv(file_path: str) -> Iterator[list[str]]:
    return csv.reader(
        (pathlib.Path(file_path)).open(),
        delimiter="|",
    )


def filter_books(books: list[list[str]], filter: str) -> list[list[str]]:
    return [
        [isbn, f"{title},{author}", quantity, price]
        for isbn, title, author, quantity, price in books
        if filter.lower() in title.lower()
    ]


def get_books_amount(books: list[list[str]]) -> list[tuple[str | float, ...]]:
    return [
        tuple([
            book[0],
            round(float(book[-2]) * float(book[-1]), 1)
        ])
        for book in books]


if __name__ == "__main__":
    main()
