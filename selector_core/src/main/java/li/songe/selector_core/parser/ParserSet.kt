package li.songe.selector_core.parser

import li.songe.selector_core.Selector
import li.songe.selector_core.data.BinaryExpression
import li.songe.selector_core.data.CompareOperator
import li.songe.selector_core.data.ConnectOperator
import li.songe.selector_core.data.ConnectSegment
import li.songe.selector_core.data.ConnectWrapper
import li.songe.selector_core.data.PolynomialExpression
import li.songe.selector_core.data.PropertySegment
import li.songe.selector_core.data.PropertyWrapper

internal object ParserSet {
    val whiteCharParser = Parser("\u0020\t\r\n") { source, offset, prefix ->
        var i = offset
        var data = ""
        while (i < source.length && prefix.contains(source[i])) {
            data += source[i]
            i++
        }
        ParserResult(data, i - offset)
    }
    val whiteCharStrictParser = Parser("\u0020\t\r\n") { source, offset, prefix ->
        SyntaxError.assert(source, offset, prefix, "whitespace")
        whiteCharParser(source, offset)
    }
    val nameParser =
        Parser("*1234567890qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM_") { source, offset, prefix ->
            var i = offset
            val s0 = source.getOrNull(i)
            if (s0 != null && !prefix.contains(s0)) {
                return@Parser ParserResult("")
            }
            SyntaxError.assert(source, i, prefix, "*0-9a-zA-Z_")
            var data = source[i].toString()
            i++
            if (data == "*") { // 范匹配
                return@Parser ParserResult(data, i - offset)
            }
            val center = "1234567890qwertyuiopasdfghjklzxcvbnmQWERTYUIOPASDFGHJKLZXCVBNM_."
            while (i < source.length) {
//                . 不能在开头和结尾
                if (data[i - offset - 1] == '.') {
                    SyntaxError.assert(source, i, prefix, "[0-9a-zA-Z_]")
                }
                if (center.contains(source[i])) {
                    data += source[i]
                } else {
                    break
                }
                i++
            }
            ParserResult(data, i - offset)
        }

    val combinatorOperatorParser =
        Parser(ConnectOperator.allSubClasses.joinToString("") { it.key }) { source, offset, _ ->
            val operator = ConnectOperator.allSubClasses.find { subOperator ->
                source.startsWith(
                    subOperator.key,
                    offset
                )
            } ?: SyntaxError.throwError(source, offset, "ConnectOperator")
            return@Parser ParserResult(operator, operator.key.length)
        }

    val integerParser = Parser("1234567890") { source, offset, prefix ->
        var i = offset
        SyntaxError.assert(source, i, prefix, "number")
        var s = ""
        while (prefix.contains(source[i])) {
            s += source[i]
            i++
        }
        ParserResult(s.toInt(), i - offset)
    }


    //    [+-][a][n[^b]]
    val monomialParser = Parser("+-1234567890n") { source, offset, prefix ->
        var i = offset
        SyntaxError.assert(source, i, prefix)
        /**
         * one of 1, -1
         */
        val signal = when (source[i]) {
            '+' -> {
                i++
                1
            }

            '-' -> {
                i++
                -1
            }

            else -> 1
        }
        i += whiteCharParser(source, i).length
        // [a][n[^b]]
        SyntaxError.assert(source, i, integerParser.prefix + "n")
        val coefficient =
            if (integerParser.prefix.contains(source[i])) {
                val coefficientResult = integerParser(source, i)
                i += coefficientResult.length
                coefficientResult.data
            } else {
                1
            } * signal
        // [n[^b]]
        if (i < source.length && source[i] == 'n') {
            i++
            if (i < source.length && source[i] == '^') {
                i++
                val powerResult = integerParser(source, i)
                i += powerResult.length
                return@Parser ParserResult(Pair(powerResult.data, coefficient), i - offset)
            } else {
                return@Parser ParserResult(Pair(1, coefficient), i - offset)
            }
        } else {
            return@Parser ParserResult(Pair(0, coefficient), i - offset)
        }
    }

    //    ([+-][a][n[^b]] [+-][a][n[^b]])
    val expressionParser = Parser("(0123456789n") { source, offset, prefix ->
        var i = offset
        SyntaxError.assert(source, i, prefix)
        val monomialResultList = mutableListOf<ParserResult<Pair<Int, Int>>>()
        when (source[i]) {
            '(' -> {
                i++
                i += whiteCharParser(source, i).length
                SyntaxError.assert(source, i, monomialParser.prefix)
                while (source[i] != ')') {
                    if (monomialResultList.size > 0) {
                        SyntaxError.assert(source, i, "+-")
                    }
                    val monomialResult = monomialParser(source, i)
                    monomialResultList.add(monomialResult)
                    i += monomialResult.length
                    i += whiteCharParser(source, i).length
                    if (i >= source.length) {
                        SyntaxError.assert(source, i, ")")
                    }
                }
                i++
            }

            else -> {
                val monomialResult = monomialParser(source, i)
                monomialResultList.add(monomialResult)
                i += monomialResult.length
            }
        }
        val map = mutableMapOf<Int, Int>()
        monomialResultList.forEach { monomialResult ->
            val (power, coefficient) = monomialResult.data
            map[power] = (map[power] ?: 0) + coefficient
        }
        map.mapKeys { power ->
            if (power.key > 1) {
                SyntaxError.throwError(source, offset, "power must be 0 or 1")
            }
        }
        ParserResult(PolynomialExpression(map[1] ?: 0, map[0] ?: 0), i - offset)
    }

    //    [+-><](a*n^b)
    val combinatorParser = Parser(combinatorOperatorParser.prefix) { source, offset, _ ->
        var i = offset
        val operatorResult = combinatorOperatorParser(source, i)
        i += operatorResult.length
        var expressionResult: ParserResult<PolynomialExpression>? = null
        if (i < source.length && expressionParser.prefix.contains(source[i])) {
            expressionResult = expressionParser(source, i)
            i += expressionResult.length
        }
        ParserResult(
            ConnectSegment(
                operatorResult.data,
                expressionResult?.data ?: PolynomialExpression()
            ), i - offset
        )
    }

    val attrOperatorParser =
        Parser(CompareOperator.allSubClasses.joinToString("") { it.key }) { source, offset, _ ->
            val operator = CompareOperator.allSubClasses.find { SubOperator ->
                source.startsWith(SubOperator.key, offset)
            } ?: SyntaxError.throwError(source, offset, "CompareOperator")
            ParserResult(operator, operator.key.length)
        }
    val stringParser = Parser("`") { source, offset, prefix ->
        var i = offset
        SyntaxError.assert(source, i, prefix)
        i++
        var data = ""
        while (source[i] != '`') {
            if (i == source.length - 1) {
                SyntaxError.assert(source, i, "`")
                break
            }
            if (source[i] == '\\') {
                i++
                SyntaxError.assert(source, i)
                if (source[i] == '`') {
                    data += source[i]
                    SyntaxError.assert(source, i + 1)
                } else {
                    data += '\\' + source[i].toString()
                }
            } else {
                data += source[i]
            }
            i++
        }
        i++
        ParserResult(data, i - offset)
    }

    val propertyParser =
        Parser((('0'..'9') + ('a'..'z') + ('A'..'Z')).joinToString("") + "_") { source, offset, prefix ->
            var i = offset
            SyntaxError.assert(source, i, prefix)
            var data = source[i].toString()
            i++
            while (i < source.length) {
                if (!prefix.contains(source[i])) {
                    break
                }
                data += source[i]
                i++
            }
            ParserResult(data, i - offset)
        }

    val valueParser = Parser("tfn`1234567890") { source, offset, prefix ->
        var i = offset
        SyntaxError.assert(source, i, prefix)
        val value: Any? = when (source[i]) {
            't' -> {
                i++
                "rue".forEach { c ->
                    SyntaxError.assert(source, i, c.toString())
                    i++
                }
                true
            }

            'f' -> {
                i++
                "alse".forEach { c ->
                    SyntaxError.assert(source, i, c.toString())
                    i++
                }
                false
            }

            'n' -> {
                i++
                "ull".forEach { c ->
                    SyntaxError.assert(source, i, c.toString())
                    i++
                }
                null
            }

            '`' -> {
                val s = stringParser(source, i)
                i += s.length
                s.data
            }

            in "1234567890" -> {
                val n = integerParser(source, i)
                i += n.length
                n.data
            }

            else -> {
                SyntaxError.throwError(source, i, prefix)
            }
        }
        ParserResult(value, i - offset)
    }

    val attrParser = Parser("[") { source, offset, prefix ->
        var i = offset
        SyntaxError.assert(source, i, prefix)
        i++
        val parserResult = propertyParser(source, i)
        i += parserResult.length
        val operatorResult = attrOperatorParser(source, i)
        i += operatorResult.length
        val valueResult = valueParser(source, i)
        i += valueResult.length
        SyntaxError.assert(source, i, "]")
        i++
        ParserResult(
            BinaryExpression(
                parserResult.data,
                operatorResult.data,
                valueResult.data
            ), i - offset
        )
    }

    val selectorUnitParser = Parser { source, offset, _ ->
        var i = offset
        var match = false
        if (source.getOrNull(i) == '@') {
            match = true
            i++
        }
        val nameResult = nameParser(source, i)
        i += nameResult.length
        val attrList = mutableListOf<BinaryExpression>()
        while (i < source.length && source[i] == '[') {
            val attrResult = attrParser(source, i)
            i += attrResult.length
            attrList.add(attrResult.data)
        }
        if (nameResult.length == 0 && attrList.size == 0) {
            SyntaxError.throwError(source, i, "[")
        }
        ParserResult(PropertySegment(match, nameResult.data, attrList), i - offset)
    }

    val connectSelectorParser = Parser { source, offset, _ ->
        var i = offset
        i += whiteCharParser(source, i).length
        val topSelector = selectorUnitParser(source, i)
        i += topSelector.length
        val selectorList = mutableListOf<Pair<ConnectSegment, PropertySegment>>()
        while (i < source.length && whiteCharParser.prefix.contains(source[i])) {
            i += whiteCharStrictParser(source, i).length
            val combinator = if (combinatorParser.prefix.contains((source[i]))) {
                val combinatorResult = combinatorParser(source, i)
                i += combinatorResult.length
                i += whiteCharStrictParser(source, i).length
                combinatorResult.data
            } else {
                ConnectSegment(polynomialExpression = PolynomialExpression(1, 0))
            }
            val selectorResult = selectorUnitParser(source, i)
            i += selectorResult.length
            selectorList.add(combinator to selectorResult.data)
        }
        ParserResult(topSelector.data to selectorList, i - offset)
    }

    val endParser = Parser { source, offset, _ ->
        if (offset != source.length) {
            SyntaxError.throwError(source, offset, "end")
        }
        ParserResult(Unit, 0)
    }

    val selectorParser: (String) -> Selector = { source ->
        var i = 0
        i += whiteCharParser(source, i).length
        val combinatorSelectorResult = connectSelectorParser(source, i)
        i += combinatorSelectorResult.length

        i += whiteCharParser(source, i).length
        i += endParser(source, i).length
        val data = combinatorSelectorResult.data
        val propertySelectorList = mutableListOf<PropertySegment>()
        val combinatorSelectorList = mutableListOf<ConnectSegment>()
        propertySelectorList.add(data.first)
        data.second.forEach {
            propertySelectorList.add(it.second)
            combinatorSelectorList.add(it.first)
        }
        val wrapperList = mutableListOf(PropertyWrapper(propertySelectorList.first()))
        combinatorSelectorList.forEachIndexed { index, combinatorSelector ->
            val combinatorSelectorWrapper =
                ConnectWrapper(combinatorSelector, wrapperList.last())
            val propertySelectorWrapper =
                PropertyWrapper(propertySelectorList[index + 1], combinatorSelectorWrapper)
            wrapperList.add(propertySelectorWrapper)
        }
        Selector(wrapperList.last())
    }
}