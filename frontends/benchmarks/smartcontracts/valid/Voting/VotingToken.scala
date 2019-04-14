import stainless.smartcontracts._
import stainless.annotation._
import stainless.equations._
import stainless.collection._
import stainless.lang.StaticChecks._
import stainless.lang.old
import stainless.lang.ghost
import stainless.lang.MutableMap
import scala.language.postfixOps

import scala.annotation.meta.field

import SafeMath._
import Util._
import VotingTokenLemmas._
import VotingTokenInvariant._

trait VotingToken extends Contract {
  var rewardToken: Address
  var opened: Boolean
  var closed: Boolean
  var votingAddresses: List[Address]
  var numberOfAlternatives: Uint256
  var name: String
  var symbol: String
  var decimals: Uint8
  var totalSupply: Uint256

  val balances: MutableMap[Address, Uint256]
  val allowed: MutableMap[Address, MutableMap[Address, Uint256]]

  @ghost
  var participants: List[Address]

  @solidityView
  @solidityPublic
  def balanceOf(_owner: Address) = balances(_owner)
  @solidityView
  @solidityPublic
  def allowance(_owner: Address, _spender: Address) = allowed(_owner)(_spender)

  @ghost
  final def addParticipant(p: Address) = {
    if (!participants.contains(p))
      participants = p :: participants
  }

  @solidityPublic
  final def approve(_spender: Address, _value: Uint256) = {
    require( standardTokenInvariant(this) )
    allowed(Msg.sender)(_spender) = _value
    true
  } ensuring { _ =>
    standardTokenInvariant(this)
  }

  // Owned contract
  var owner: Address

  def constructor(
    _name: String,
    _symbol: String,
    _decimals: Uint8,
    _rewardToken: Address,
    _votingAddresses: List[Address],
    _numberOfAlternatives: Uint256
  ): Unit = {
    // initial values given by Solidity (this part needs to be injected automatically)
    require(
      totalSupply == Uint256.ZERO &&
      balances == MutableMap.withDefaultValue(() => Uint256.ZERO) &&
      opened == false &&
      closed == false
    )

    // ghost initialization of participants
    ghost {
      participants = List()
    }

    name = _name
    symbol = _symbol
    decimals = _decimals

    dynRequire(length(_votingAddresses) == _numberOfAlternatives)
    numberOfAlternatives = _numberOfAlternatives
    rewardToken = _rewardToken
    votingAddresses = _votingAddresses

    dynRequire(Msg.sender != Address(0))
    owner = Msg.sender

    assert(ownerInvariant(this))
    assert(openOrCloseInvariant(this))
    assert(standardTokenInvariant(this))
  }

  @solidityPublic
  final def transfer(_to: Address, _value: Uint256) = {
    require(votingTokenInvariant(this))

    dynRequire(_to != Address(0))
    dynRequire(_value <= balances(Msg.sender))

    ghost {
      addParticipant(_to)
      addParticipant(Msg.sender)
    }

    // balances mapping before any update
    @ghost val b0 = balances.duplicate

    // code to remove balance from `Msg.sender`
    balances(Msg.sender) = sub(balances(Msg.sender), _value)

    @ghost val b1 = balances.duplicate

    // code to add balance to recipient `_to`
    balances(_to) = add(balances(_to), _value)

    assert((
      sumBalances(participants, balances)                                                       ==| balancesUpdatedLemma(participants, b1, _to, add(b1(_to), _value)) |:
      sumBalances(participants, b1) - b1(_to) + add(b1(_to), _value)                            ==| subSwap(sumBalances(participants,b1), b1(_to), _value) |:
      sumBalances(participants, b1) + _value                                                    ==|
        (balancesUpdatedLemma(participants, b0, Msg.sender, sub(b0(Msg.sender), _value)) &&
        sumBalances(participants, b1) == sumBalances(participants, b0) - b0(Msg.sender) + sub(b0(Msg.sender), _value))
        |:
      sumBalances(participants, b0) - b0(Msg.sender) + sub(b0(Msg.sender), _value) + _value     ==| trivial |:
      sumBalances(participants, b0) - b0(Msg.sender) + (sub(b0(Msg.sender), _value) + _value)   ==| (sub(b0(Msg.sender), _value) + _value == b0(Msg.sender)) |:
      sumBalances(participants, b0) - b0(Msg.sender) + b0(Msg.sender)                           ==| trivial |:
      sumBalances(participants, b0)                                                             ==| trivial |:
      totalSupply
    ).qed)

    assert(sumBalances(participants, balances) == totalSupply)

    _rewardVote(Msg.sender, _to, _value)
    true
  } ensuring{ _ =>
    votingTokenInvariant(this)
  }

  @solidityPublic
  final def transferFrom(_from: Address, _to: Address, _value: Uint256) = {
    require(votingTokenInvariant(this))

    dynRequire(_to != Address(0))
    dynRequire(_value <= balances(_from))
    dynRequire(_value <= allowed(_from)(Msg.sender))

    ghost {
      addParticipant(_from)
      addParticipant(_to)
    }

    // balances mapping before any update
    @ghost val b0 = balances.duplicate

    // code to remove balance from `_from` address
    balances(_from) = sub(balances(_from), _value)

    // balances mapping before after the first update, before the second update
    @ghost val b1 = balances.duplicate

    // code to add balance to recipient `_to`
    balances(_to) = add(balances(_to), _value)

    // code to remove from allowance
    allowed(_from)(Msg.sender) = sub(allowed(_from)(Msg.sender), _value)

    assert((
      sumBalances(participants, balances)                                             ==| balancesUpdatedLemma(participants, b1, _to, add(b1(_to), _value)) |:
      sumBalances(participants, b1) - b1(_to) + add(b1(_to), _value)                  ==| subSwap(sumBalances(participants,b1), b1(_to), _value) |:
      sumBalances(participants, b1) + _value                                          ==|
        (balancesUpdatedLemma(participants, b0, _from, sub(b0(_from), _value)) &&
        sumBalances(participants, b1) == sumBalances(participants, b0) - b0(_from) + sub(b0(_from), _value))
        |:
      sumBalances(participants, b0) - b0(_from) + sub(b0(_from), _value) + _value     ==| trivial |:
      sumBalances(participants, b0) - b0(_from) + (sub(b0(_from), _value) + _value)   ==| (sub(b0(_from), _value) + _value == b0(_from)) |:
      sumBalances(participants, b0) - b0(_from) + b0(_from)                           ==| trivial |:
      sumBalances(participants, b0)                                                   ==| trivial |:
      totalSupply
    ).qed)

    _rewardVote(_from, _to, _value)
  } ensuring{ _ =>
    votingTokenInvariant(this)
  }

  @solidityView
  @solidityPublic
  final def onlyOwner: Boolean = Msg.sender == owner

  @solidityPublic
  final def transferOwnership(newOwner: Address) = {
    dynRequire(onlyOwner && newOwner != Address(0))

    owner = newOwner
  } ensuring {
    _ => newOwner != Address(0)
  }

  @solidityPublic
  final def mint(_to: Address, _amount: Uint256) = {
    require(votingTokenInvariant(this))
    dynRequire(onlyOwner)
    dynRequire(!opened)

    ghost {
      addParticipant(_to)
    }

    val newBalance = add(balances(_to) , _amount)
    @ghost val b0 = balances.duplicate
    @ghost val oldSupply = totalSupply

    // two lines of code
    totalSupply = add(totalSupply, _amount)
    balances(_to) = newBalance

    assert((
      sumBalances(participants, balances)                   ==| balancesUpdatedLemma(participants, b0, _to, newBalance) |:
      sumBalances(participants, b0) - b0(_to) + newBalance  ==| subSwap(sumBalances(participants, b0), b0(_to), _amount) |:
      sumBalances(participants, b0) + _amount               ==| trivial |:
      oldSupply + _amount                                   ==| trivial |:
      totalSupply)
        qed
    )

    assert(participantsProp(participants, balances))
    assert(sumBalances(participants, balances) == totalSupply)
    assert(ownerInvariant(this))
    assert(openOrCloseInvariant(this))

    true
  } ensuring { _ =>
    votingTokenInvariant(this) &&
    old(this).owner == this.owner
  }

  @solidityPublic
  final def open() = {
    require(votingTokenInvariant(this))
    dynRequire(onlyOwner)
    dynRequire(!opened)

    opened = true
  } ensuring { _ =>
    votingTokenInvariant(this) &&
    old(this).owner == this.owner
  }

  @solidityPublic
  final def close() = {
    require(votingTokenInvariant(this))
    dynRequire(onlyOwner)
    dynRequire(opened)
    dynRequire(!closed)

    closed = true
  } ensuring { _ =>
    votingTokenInvariant(this) &&
    old(this).owner == this.owner
  }

  // FIXME: tokens should be a list of addresses
  // private def transferToken(tokens: List[ERC20], i: Uint256): Unit = {
  //   // decreases(max(length(tokens) - i, 0))

  //   if (i < length(tokens)) {
  //     get(tokens,i).transfer(owner, get(tokens,i).balanceOf(address(this)))
  //     transferToken(tokens, i + Uint256.ONE)
  //   }
  // }

  // FIXME: tokens should be a list of addresses
  // def destroy(tokens: List[ERC20]) = {
  //   require(votingTokenInvariant(this))
  //   dynRequire(onlyOwner)

  //   transferToken(tokens, Uint256.ZERO)
  //   selfdestruct(owner)

  // } ensuring { _ =>
  //   votingTokenInvariant(this) &&
  //   old(this).owner == this.owner
  // }

  @solidityPrivate
  final def _rewardVote(_from: Address, _to: Address, _value: Uint256): Unit = {
    require(votingTokenInvariant(this))

    if(_isVotingAddress(_to)) {
      dynRequire(opened && !closed)
      val rewardTokens:Uint256 = div(_value, Uint256("100"))
      // FIXME: uncomment when we implement external calls
      // dynRequire(rewardToken.transfer(_from, rewardTokens))
    }

  } ensuring { _ =>
    votingTokenInvariant(this) &&
    old(this).owner == this.owner
  }

  @solidityView
  @solidityPrivate
  final def _isVotingAddressFrom(i: Uint256, votingAddress: Address): Boolean = {
    // decreases(max(length(votingAddresses) - i, Uint256.ZERO))

    if (i >= length(votingAddresses)) false
    else if (get(votingAddresses,i) == votingAddress) true
    else _isVotingAddressFrom(i + Uint256.ONE, votingAddress)
  }

  @solidityView
  @solidityPrivate
  private final def _isVotingAddress(votingAddress: Address) = {
    require(votingTokenInvariant(this))

    _isVotingAddressFrom(Uint256.ZERO, votingAddress)
  } ensuring { _ =>
    votingTokenInvariant(this) &&
    old(this).owner == this.owner
  }

}
