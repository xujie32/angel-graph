package com.tencent.angel.graph.utils

import scala.reflect.ClassTag
import scala.{specialized => spec}
import com.google.common.hash.Hashing


class FastArray[@specialized V: ClassTag](initialSize: Int = 64) {
  private var _numElements = 0
  private var _array: Array[V] = _

  // NB: This must be separate from the declaration, otherwise the specialized parent class
  // will get its own array with the same initial size.
  _array = new Array[V](initialSize)

  def apply(index: Int): V = {
    require(index < _numElements)
    _array(index)
  }

  def +=(value: V): Unit = {
    if (_numElements == _array.length) {
      resize(_array.length * 2)
    }
    _array(_numElements) = value
    _numElements += 1
  }

  def capacity: Int = _array.length

  def length: Int = _numElements

  def size: Int = _numElements

  def iterator: Iterator[V] = new Iterator[V] {
    var index = 0
    override def hasNext: Boolean = index < _numElements
    override def next(): V = {
      if (!hasNext) {
        throw new NoSuchElementException
      }
      val value = _array(index)
      index += 1
      value
    }
  }

  def foreach(func: V => Unit): Unit = {
    val iter = iterator
    while (iter.hasNext) {
      func(iter.next())
    }
  }

  /** Gets the underlying array backing this vector. */
  def array: Array[V] = _array

  /** Trims this vector so that the capacity is equal to the size. */
  def trim(): FastArray[V] = resize(size)

  /** Resizes the array, dropping elements if the total length decreases. */
  def resize(newLength: Int): FastArray[V] = {
    _array = copyArrayWithLength(newLength)
    if (newLength < _numElements) {
      _numElements = newLength
    }
    this
  }

  /** Return a trimmed version of the underlying array. */
  def toArray: Array[V] = {
    copyArrayWithLength(size)
  }

  private def copyArrayWithLength(length: Int): Array[V] = {
    val copy = new Array[V](length)
    _array.copyToArray(copy)
    copy
  }
}